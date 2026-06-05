# 压测说明与基线指标

## 运行方式

确保依赖已启动且应用以**基线配置**（`demo.optimize.*` 全 false）运行：

```bash
# 1. 启动第三方依赖
docker-compose up -d mysql redis

# 2. 启动应用（需 Java 21）
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -DskipTests spring-boot:run

# 3. 跑压测并生成 HTML 报告
cd jmeter
jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/
```

可用 `-J` 覆盖参数：线程数 `threads`(默认 200)、循环 `loops`(默认 5)、`host`、`port`。
例如加压：`jmeter -n -t order_list_test.jmx -Jthreads=400 -Jloops=10 -l result.jtl -e -o report/`

## 如何阅读报告

- 命令行 `summary =` 行：总请求数、吞吐量(req/s)、Avg/Min/Max 响应时间、Err 错误率。
- HTML 报告 `report/index.html`：看 **Statistics** 表的 Average、Throughput、Error%、以及 99th pct(TP99)。
- `report/statistics.json` 的 `Total` 节点字段：`meanResTime`(平均)、`pct3ResTime`(TP99)、`throughput`、`errorPct`。

## 模拟数据库延迟（关键前提）

本机 docker MySQL 单次查询是亚毫秒级，N+1 的 61 次查询累计也只有 ~28ms，无法复现原文
“N+1 把延迟累加成数秒”的场景。为此项目引入 `demo.sim-db-latency-ms`（默认 5ms）：
通过 MyBatis 拦截器在**每条 SQL 执行前** sleep 固定毫秒，模拟远程 DB 网络往返。

- 该延迟发生在连接被占用期间，因此既放大 N+1 与批量的差距，也能制造真实的连接池争用（US-011）。
- 设 `demo.sim-db-latency-ms=0` 即关闭，退回纯本机真实测量。
- 下列基线即在 5ms 延迟下测得。

参考：关闭延迟（=0）时的纯本机基线约为 199 req/s、avg 28.7ms、TP99 58ms、0% 错误。

## 基线指标（未优化，所有开关 false，sim-db-latency-ms=5）

测试条件：200 线程 × 5 循环 = 1000 次请求，`GET /orders?pageNo=1&pageSize=20`（每请求触发 N+1，约 61 次 DB 查询）。
环境：本机 docker MySQL 8.0 / Redis，单机，每条 SQL 注入 5ms 模拟延迟。

| 指标 | 实测值 |
|------|--------|
| 总请求数 | 1000 |
| 吞吐量 | ~21.9 req/s |
| 平均响应时间 | ~7979 ms |
| TP90 / TP95 / TP99 | 10346 / 10854 / 11851 ms |
| 错误率 | 0% |

这组数字与原文“慢系统”的量级一致（原文 38 req/s / 4200ms）。错误率为 0 是因为
HikariCP 默认 connection-timeout 为 30s，请求虽慢但仍在超时内完成；US-011 调小超时后
会观察到快速失败带来的错误率变化。

每个优化阶段（US-010 起）都会用同一脚本、同一 5ms 延迟复测，记录优化前后对比。

## US-010 N+1 → 批量查询对比（demo.optimize.batch-query）

打开开关后，`fillOrderDetails` 的逐条 `selectById` 改为按本页 id 收集后各发一次 `IN` 批量查询
（`selectBatchIds` + 内存 Map 匹配）。单页查询次数与页大小解耦：

| 口径 | batch-query=false（N+1） | batch-query=true（批量） |
|------|--------------------------|--------------------------|
| 单请求 SQL 次数（含分页 count） | 62（1 count + 1 列表 + 20×3 明细） | 5（1 count + 1 列表 + 3 批量 IN） |
| 明细查询口径 | 61 | 4 |

同一脚本（200 线程 × 5 循环 = 1000 请求）、同 5ms 模拟延迟下压测对比：

| 指标 | 优化前（false） | 优化后（true） |
|------|----------------|---------------|
| 吞吐量 | ~21.9 req/s | ~194.3 req/s（↑ ~8.9×） |
| 平均响应时间 | ~7979 ms | ~53.7 ms（↓ ~99%） |
| TP90 / TP95 / TP99 | 10346 / 10854 / 11851 ms | 88 / 105 / 137 ms |
| 错误率 | 0% | 0% |

复现命令：

```bash
# 优化后实例（开关打开 + 5ms 延迟）
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--demo.optimize.batch-query=true --demo.sim-db-latency-ms=5"

# 压测（脚本已参数化 port，可 -Jport 指向不同实例）
cd jmeter && jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/
```

结论：N+1 的本质是「查询次数随页大小线性增长」，每次又叠加固定网络往返延迟；批量查询把
60 次明细往返压成 3 次，串行延迟链被砍断，是这条优化曲线里收益最大的一步。

## US-011 HikariCP 连接池调优对比（demo.optimize.hikari-tuning）

打开开关把连接池从 10 连接 / 30s 超时调到 30 连接 / 10 最小空闲 / 3s 超时。
同脚本（200 线程 × 5 循环 = 1000 请求）、N+1 未关（batch-query=false）、5ms 延迟下对比：

| 指标 | tuning=false（10 连接） | tuning=true（30 连接） |
|------|-------------------------|------------------------|
| 吞吐量 | ~21.9 req/s | ~69.7 req/s（↑ ~3.2×） |
| 平均响应时间 | ~7979 ms | ~1939 ms |
| TP90 / TP95 / TP99 | 10346 / 10854 / 11851 ms | 2880 / 3147 / 3490 ms |
| 错误率 | 0% | 0% |

连接超时日志解读、连接数经验公式、leak-detection-threshold 说明见 [../docs/HIKARICP.md](../docs/HIKARICP.md)。
要点：把超时从 30s 调到 3s 会把“慢”变成“快速失败的错”——基线 0% 错误是因 30s 超时够长（慢而不错），
实测 10 连接 + 3s 超时 + 200ms 延迟 + 200 并发时错误率达 82.5%，日志显示 `total=10, active=10, idle=0, waiting=189`。
