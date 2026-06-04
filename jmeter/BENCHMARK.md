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

## 基线指标（未优化，所有开关 false）

测试条件：200 线程 × 5 循环 = 1000 次请求，`GET /orders?pageNo=1&pageSize=20`（每请求触发 N+1，约 61 次 DB 查询）。
环境：本机 docker MySQL 8.0 / Redis，单机。

| 指标 | 实测值 |
|------|--------|
| 总请求数 | 1000 |
| 吞吐量 | ~199 req/s |
| 平均响应时间 | ~28.7 ms |
| TP90 / TP95 / TP99 | 42 / 47 / 58 ms |
| 错误率 | 0% |

## 重要说明：本机环境 vs 原文数字

原文给出的基线是 **38 req/s、4200ms、12% 错误率**，那是在数据库有真实网络/磁盘延迟（每次查询几十毫秒）的环境下，N+1 把 61 次查询的延迟累加放大的结果。

本项目跑在**本机 docker MySQL**上，单次查询是亚毫秒级，所以 61 次 N+1 查询累计也只有 ~28ms，错误率为 0。**这是真实测量结果，未套用原文数字。**

这带来一个客观影响：在本机环境下，N+1 等优化的**绝对**收益不像原文那么夸张。要让优化效果更明显，可在后续阶段任选其一放大对比：

1. 给单条查询人为加延迟（模拟远程 DB），例如在 Mapper 层 sleep 几毫秒——最贴近原文场景；
2. 提高压力：增大 `threads`/`loops`、增大 `pageSize`（N+1 倍数随每页条数线性放大）；
3. 关注**相对**提升（优化前后 req/s 与 TP99 的比值）而非绝对毫秒数。

每个优化阶段（US-010 起）都会用同一脚本复测，记录优化前后对比。
