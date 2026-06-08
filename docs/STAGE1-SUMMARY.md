# 阶段一汇总：从慢到快的完整优化曲线（US-016）

这份文档把 US-005 ~ US-015 串成一条线：从一个刻意写慢的订单系统出发，逐个打开 `demo.optimize.*`
开关，看每一步带来的提升、对应的压测数字，以及面试里可以展开讲的知识点。最后给出「如何按阶段
逐个打开开关复现整条曲线」的操作顺序。

## 0. 两条曲线 & 一个前提

阶段一其实是**两条独立的优化线**，针对两个不同接口，别把它们的数字混在一起：

| 优化线 | 接口 | 慢在哪 | 优化开关 |
| --- | --- | --- | --- |
| **读路径** | `GET /orders`（订单列表） | N+1 查询 + 连接池争用 + 热点重复查库 | batch-query → hikari-tuning → cache |
| **写路径** | `POST /orders`（下单） | 非核心任务（短信/邮件/积分）同步串行 | async → mq |

**关于指标的诚实说明**：原文讲的是 `38 req/s、4200ms、12% 错误 → 500+ req/s、~80ms、0% 错误`
这条叙事曲线。本机 docker MySQL 是亚毫秒查询，**无法天然复现**原文「N+1 把延迟累成数秒」的场景，
因此项目引入 `demo.sim-db-latency-ms`（默认 5ms，每条 SQL 前 sleep 模拟远程 DB 往返）来放大对比。
**下表全部是本项目在 5ms 延迟下的真实实测值**（与原文量级一致，但不是照抄原文数字）。压测前提统一为：
200 线程 × 5 循环 = 1000 请求，单机 docker MySQL 8.0 / Redis。

## 1. 起点：慢系统长什么样（US-005 / US-008 / US-009）

- **US-005** 把订单列表刻意写成 N+1：一页 20 条订单，先查 1 次列表，再对每条订单分别查
  user/product/logistics，合计 `1 + 20×3 = 61` 次 DB 往返。
- **US-008** 压出基线：

  | 指标 | 基线（全 false，5ms 延迟） |
  | --- | --- |
  | 吞吐量 | ~21.9 req/s |
  | 平均响应 | ~7979 ms |
  | TP90 / TP95 / TP99 | 10346 / 10854 / 11851 ms |
  | 错误率 | 0%（HikariCP 默认 30s 超时够长，慢而不错） |

- **US-009** 用 Arthas `trace` 定位：`fillOrderDetails` 占 ~99% 耗时，三个 Mapper 各 `[20 times]`；
  工作线程几乎全 `TIMED_WAITING` 阻塞在等 DB。**反直觉结论**：瓶颈是「串行 61 次往返 + 仅 10 条连接」，
  不是线程数，调大 Tomcat 线程只会让更多线程抢 10 条连接、排队更长、TP99 反升。详见 [ARTHAS.md](ARTHAS.md)。

## 2. 读路径优化

### 阶段 R1 · N+1 → 批量查询（US-010，`batch-query`）

把逐条 `selectById` 改成按本页 id 收集后各发一次 `IN` 批量查询 + 内存 Map 匹配，单请求明细查询 `61 → 4`。

- **开关**：`--demo.optimize.batch-query=true`
- **压测命令**：
  ```bash
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -DskipTests spring-boot:run \
    -Dspring-boot.run.arguments="--demo.optimize.batch-query=true --demo.sim-db-latency-ms=5"
  cd jmeter && jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/
  ```
- **对比**（同 5ms 延迟）：

  | 指标 | 前(false) | 后(true) |
  | --- | --- | --- |
  | 吞吐量 | ~21.9 req/s | ~194.3 req/s（**↑ ~8.9×**） |
  | 平均响应 | ~7979 ms | ~53.7 ms |
  | TP99 | 11851 ms | 137 ms |

- **面试知识点**：N+1 的本质是「查询次数随结果集线性增长」，每次又叠加固定网络往返；批量把 60 次
  明细往返压成 3 次，砍断串行延迟链——**这是整条曲线里收益最大的一步**。延伸：MyBatis-Plus `selectBatchIds`、
  `IN` 的参数量上限与分批、能否用 JOIN 替代（聚合 VO 的取舍）。

### 阶段 R2 · HikariCP 连接池调优（US-011，`hikari-tuning`）

把连接池从 `10 连接 / 30s 超时` 调到 `30 连接 / 10 最小空闲 / 3s 超时`。

- **开关**：`--demo.optimize.hikari-tuning=true`
- **对比**（N+1 未关、5ms 延迟，演示连接池单独的作用）：

  | 指标 | 前(10 连接) | 后(30 连接) |
  | --- | --- | --- |
  | 吞吐量 | ~21.9 req/s | ~69.7 req/s（**↑ ~3.2×**） |
  | 平均响应 | ~7979 ms | ~1939 ms |
  | TP99 | 11851 ms | 3490 ms |

- **面试知识点**：连接数经验公式 `连接数 ≈ core数×2 + 有效磁盘数`，防死锁下限 `Tn×(Cm-1)+1`；
  `connection-timeout` 从 30s 调到 3s 会把「慢」变成「**快速失败的错**」——实测 10 连接 + 3s 超时 +
  200ms 延迟 + 200 并发时错误率 82.5%，日志 `total=10, active=10, idle=0, waiting=189`。
  `leak-detection-threshold` 抓连接泄漏。详见 [HIKARICP.md](HIKARICP.md)。

### 阶段 R3 · Redis 缓存热点商品（US-012 / US-013，`cache`）

商品查询走 `@Cacheable(product, key=#id)`，把「商品」这类往返从 MySQL 移到 Redis。

- **开关**：`--demo.optimize.cache=true`（缓存装配在 N+1 逐条路径上，演示时配 `batch-query=false`）
- **实测**：预热后单请求 `~0.487s → ~0.145s`；N+1 路径每请求 MySQL 往返 `61 → 41`（商品 20 次转 Redis 命中）；
  `product::7` TTL≈600s 证明 `entryTtl(10min)` 生效。
- **面试知识点**：缓存一致性三方案——延迟双删、订阅 binlog、短过期兜底（含并发读写回填旧值的竞态复现），
  空值穿透（`unless=#result==null`）、`@class` 类型信息反序列化。详见 [CACHE-CONSISTENCY.md](CACHE-CONSISTENCY.md)。

> 缓存与批量是两条不同提速轴：batch-query 把往返次数压成常数，cache 把其中商品类往返移到 Redis。
> 单独开缓存仍受剩余 41 次往返串行链制约，收益不及 batch-query 那一步。

## 3. 写路径优化（下单接口 `POST /orders`）

基线下单把短信(500ms)+邮件(300ms)+积分(100ms) 全同步串行，用户要等全部跑完才拿到响应。

### 阶段 W1 · 非核心逻辑异步化 @Async（US-014，`async`）

事务提交后发布 `OrderPlacedEvent`，由 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("orderExecutor")`
丢进线程池；核心回滚则不投递。

- **开关**：`--demo.optimize.async=true`
- **对比**（下单接口 `elapsedMs`，sim-db-latency=0）：

  | 模式 | 下单响应 |
  | --- | --- |
  | async=false（同步） | ~912~953 ms（含 sms500+email300+points100） |
  | async=true（异步） | ~3~5 ms（预热后） |

- **面试知识点**：`@Async` 靠代理生效，**同 bean 自调用不异步**（所以单独成 `NonCoreTaskService`）；
  线程池参数（core/max/queue/拒绝策略）；为何用 `AFTER_COMMIT` 而非方法里直接异步（回滚不误发）；
  拒绝策略 `CallerRunsPolicy`(背压不丢) vs `AbortPolicy`(丢任务需兜底)。详见 [ASYNC.md](ASYNC.md)。

### 阶段 W2 · RabbitMQ 替代 @Async（US-015，`mq`）

非核心任务投递到 RabbitMQ，独立消费者异步处理。下单响应同样立即返回（实测 `elapsedMs=65`，主线程不等任务）。

- **开关**：`--demo.optimize.mq=true`（依赖 docker RabbitMQ；mq 优先级高于 async）
- **面试知识点**：`@Async` 进程内线程池**重启即丢任务**，MQ 把任务**持久化到 broker**，还能削峰；
  三层防丢 = **publisher confirm + 队列/交换机持久化 + 手动 ack**，配套**消费端幂等**（Redis SETNX，
  因为 at-least-once 必然重复）；失败 `nack(requeue=false)` + 死信队列防毒消息。详见 [MQ.md](MQ.md)。

## 4. 如何按阶段逐个打开开关复现整条曲线

所有开关在 `application.yml` 的 `demo.optimize.*`，仓库基线**全 false**；用命令行参数逐个打开，每开一个就
压测一次、记录数字，就能亲手画出整条优化曲线。

```bash
export JH=/opt/homebrew/opt/openjdk@21
RUN() { JAVA_HOME=$JH mvn -DskipTests spring-boot:run -Dspring-boot.run.arguments="$1"; }

# ① 基线（最慢）——读路径
RUN "--demo.sim-db-latency-ms=5"
# ② + 批量查询（读路径收益最大一步）
RUN "--demo.optimize.batch-query=true --demo.sim-db-latency-ms=5"
# ③ 单看连接池作用（N+1 不关，对照 ②）
RUN "--demo.optimize.hikari-tuning=true --demo.sim-db-latency-ms=5"
# ④ + 缓存（N+1 路径上观察商品命中）
RUN "--demo.optimize.cache=true --demo.optimize.batch-query=false --demo.sim-db-latency-ms=5"
# ⑤ 读路径全开（组合最优，预期吞吐最高）
RUN "--demo.optimize.batch-query=true --demo.optimize.hikari-tuning=true --demo.optimize.cache=true --demo.sim-db-latency-ms=5"

# 写路径（下单接口，用 curl 看 elapsedMs，不用 JMeter 列表脚本）
RUN "--demo.optimize.async=true --demo.sim-db-latency-ms=0"   # @Async
RUN "--demo.optimize.mq=true --demo.sim-db-latency-ms=0"      # RabbitMQ（需先启 docker rabbitmq）
```

每档压测：`cd jmeter && jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/`，读
`report/statistics.json` 的 `meanResTime / pct3ResTime(TP99) / throughput / errorPct`。读报告方法见
[../jmeter/BENCHMARK.md](../jmeter/BENCHMARK.md)。

> 说明：每个开关的「前后对比」数字已在各 US 单独实测（见上表与 BENCHMARK.md）。⑤「读路径全开」的
> 组合压测值本项目尚未单独跑（避免编造，留作复现练习）；原文叙事的终点是 `500+ req/s / ~80ms / 0%`，
> 而本项目单开 batch-query 一步即到 ~194 req/s / 53.7ms，组合开启预期更高。

## 5. 一页速查

| 阶段 | 开关 | 接口 | 关键对比（5ms 延迟实测） | 一句话知识点 |
| --- | --- | --- | --- | --- |
| 基线 | 全 false | GET /orders | 21.9 rps / 7979ms / TP99 11851ms | N+1：61 次串行往返 |
| R1 批量 | batch-query | GET /orders | 194.3 rps / 53.7ms / TP99 137ms | 查询次数与页大小解耦（收益最大） |
| R2 连接池 | hikari-tuning | GET /orders | 69.7 rps / 1939ms / TP99 3490ms | 连接数公式；超时调小=快速失败 |
| R3 缓存 | cache | GET /orders | 单请求 0.487s→0.145s；往返 61→41 | 热点移到 Redis；一致性三方案 |
| W1 异步 | async | POST /orders | 下单 ~912ms → ~5ms | @Async 代理；AFTER_COMMIT 不误发 |
| W2 MQ | mq | POST /orders | 下单 ~65ms 立即返回 | 持久化+confirm+手动ack+幂等 |
