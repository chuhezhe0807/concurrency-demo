# HikariCP 连接池调优开关（US-011）

开关 `demo.optimize.hikari-tuning` 控制连接池档位（由 `HikariTuningPostProcessor` 在连接池懒启动前覆盖参数）：

| 参数 | false（基线，来自 application.yml） | true（调优，代码覆盖） |
|------|-------------------------------------|------------------------|
| maximum-pool-size | 10 | 30 |
| minimum-idle | 10 | 10 |
| connection-timeout | 30000 ms | 3000 ms |

启动后开 `--logging.level.com.zaxxer.hikari=DEBUG` 并打一发请求触发连接池初始化，即可在日志看到 `HikariConfig` dump 出的真实生效值（false 档 `maximumPoolSize....10 / connectionTimeout....30000`；true 档 `....30 / ....3000`，且伴随一行 `HikariCP 调优开关已开启 -> maximumPoolSize=30, ...`）。

---

## 1. 连接超时日志：长什么样、怎么读

连接池耗尽时，拿不到连接的线程会等待 `connection-timeout`，超时后抛出 `SQLTransientConnectionException`。

复现（基线 10 连接，但把超时调短到 3s，并把每条 SQL 的模拟延迟拉到 200ms 以延长连接持有，再用 200 并发压上去）：

```bash
# 启动：10 连接(基线) + 3s 超时 + 200ms 模拟延迟 + N+1(batch 关闭)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--spring.datasource.hikari.connection-timeout=3000 --demo.sim-db-latency-ms=200"

# 200 并发压一轮
cd jmeter && jmeter -n -t order_list_test.jmx -Jthreads=200 -Jloops=1 -l result.jtl -e -o report/
```

实测日志（截取一条）：

```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
    request timed out after 3002ms (total=10, active=10, idle=0, waiting=189)
```

**怎么读这一行**——括号里的四个数字是诊断连接池的关键：
- `total=10`：池里连接总数，已达 `maximum-pool-size=10`，无法再扩。
- `active=10`：10 条全部借出使用中（每条正卡在 200ms 的 SQL 上）。
- `idle=0`：没有空闲连接可分配。
- `waiting=189`：还有 189 个线程在排队等连接——这就是 Tomcat 200 线程几乎全堵在“等连接”上的铁证。

本轮压测错误率 **82.5%（165/200 超时）**：连接不够 + 超时短 = 大量请求在 3s 内拿不到连接被直接拒绝（快速失败）。

> 反过来理解 US-008 基线为什么错误率是 0%：基线超时是 30s，请求虽然排队很久但仍在 30s 内陆续拿到连接，于是“慢而不错”。把超时调小（如这里的 3s）会把“慢”转化为“快速失败的错”——这正是 `connection-timeout` 的取舍：**早失败 vs 长等待**。

---

## 2. 优化前后对比（错误率与 TP99）

同一脚本（200 线程 × 5 循环 = 1000 请求）、N+1 未关（batch-query=false）、5ms 模拟延迟下，仅切换连接池档位：

| 指标 | hikari-tuning=false（10 连接） | hikari-tuning=true（30 连接） |
|------|-------------------------------|-------------------------------|
| 吞吐量 | ~21.9 req/s | ~69.7 req/s（↑ ~3.2×） |
| 平均响应时间 | ~7979 ms | ~1939 ms |
| TP90 / TP95 / TP99 | 10346 / 10854 / 11851 ms | 2880 / 3147 / 3490 ms |
| 错误率 | 0% | 0% |

连接从 10 提到 30，单位时间能并行执行的 SQL 多了 3 倍，排队大幅缩短，TP99 从 ~11.8s 降到 ~3.5s。

> 注意：这一步只是“把瓶颈往后挪”。真正治本是 US-010 把 N+1 的 61 次查询砍成 4 次——查询次数降下来后，10 连接也绰绰有余。连接池调优解决的是“连接不够导致的排队/超时”，不是“查询太多”本身。两者叠加才是完整优化。

---

## 3. 连接数怎么定：经验公式

连接不是越多越好——连接由数据库端的线程/内存支撑，过多连接会拖垮 DB 并增加上下文切换。常用两条经验：

1. **HikariCP 官方吞吐公式**（面向 CPU 密集型 DB 访问）：
   ```
   connections = (core_count * 2) + effective_spindle_count
   ```
   `core_count` 是数据库服务器 CPU 核数，`effective_spindle_count` 约等于磁盘数（SSD/云盘可粗略按 1 估或忽略）。一台 4 核 SSD 的 DB，经验值约 `4*2+1 ≈ 9~10` 条，常被惊讶地小——因为连接的价值在于“喂满 CPU”，而非堆数量。

2. **避免死锁的下限公式**（Tn 个线程、每线程同时最多持有 Cm 条连接时）：
   ```
   pool_size >= Tn * (Cm - 1) + 1
   ```
   保证任意时刻不会所有线程各持一半连接互相死等。

实践建议：以公式给出的小连接数为起点，结合压测观察 `waiting`/TP99 微调，而不是一上来就开几百。本项目 demo 用 30 是为了在 N+1 仍在、模拟延迟拉长连接持有的“放大”场景下看到对比效果，并非生产推荐值。

---

## 4. leak-detection-threshold：揪出忘记归还的连接

`leak-detection-threshold`（毫秒）用于排查**连接泄漏**：当一条连接被借出超过该阈值仍未归还，HikariCP 会打印一条带调用栈的告警，直接指到“是谁借了不还”。

```yaml
spring:
  datasource:
    hikari:
      leak-detection-threshold: 60000   # 借出超过 60s 未归还即告警(0=关闭)
```

要点：
- 典型成因：手动获取连接后异常路径没 `close()`、长事务、把连接对象缓存到了外部。
- 阈值不宜过小：要大于业务中最慢的正常查询/事务时长，否则会对正常慢查询误报。官方建议 ≥ 2000ms，生产常用 30s~60s。
- 它只**告警定位**、不自动修复；命中后仍需回到代码用 try-with-resources / 框架托管事务确保连接归还。
- 与 `maximum-pool-size` 配合：连接池频繁耗尽时，先用 leak-detection 确认“是真不够用”还是“有连接被泄漏占着不还”，避免盲目调大连接数掩盖泄漏。

---

压测对比表同时收录在 [jmeter/BENCHMARK.md](../jmeter/BENCHMARK.md) 的优化曲线里。
