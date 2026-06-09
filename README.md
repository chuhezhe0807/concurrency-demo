# concurrency-demo（单体阶段）

> 本分支 `main` 是项目的**阶段一：单体应用与性能调优**（US-001 ~ US-016）。
> 阶段二的**微服务拆分与分布式事务**（Nacos/Gateway/Feign、Seata 强一致、RabbitMQ 最终一致）在 **`microservices` 分支**，见该分支的 README。

一个有意写「慢」的单体应用：先用 JMeter 压出基线，再逐项打开优化开关（N+1 批量、连接池、缓存、异步、消息），对比前后差异，理解每项优化解决什么问题、代价在哪。

## 技术栈

- Java 21、Spring Boot 3.3.4（单模块，`src/` 在根目录）
- MyBatis-Plus、MySQL 8、Redis 7、RabbitMQ 3.13
- 缓存一致性方案用到 binlog 订阅（`mysql-binlog-connector-java`）
- 诊断 Arthas，压测 JMeter

## 基础设施（docker-compose）

| 服务 | 端口 | 备注 |
| --- | --- | --- |
| MySQL | 3306 | 首次启动执行 `docker/mysql/init` 建表与种子数据（user 1k / product 200 / order 30k / logistics 30k） |
| Redis | 6380 → 6379 | 宿主机 6380，容器内 6379 |
| RabbitMQ | 5672 / 15672 | 15672 管理台 `guest/guest` |

```bash
docker compose up -d mysql redis rabbitmq
```

## 如何运行

> 本机用 Java 21，例如 `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`

```bash
docker compose up -d mysql redis rabbitmq
mvn spring-boot:run            # http://localhost:8080
```

## 优化开关（feature flag）

基线下全部关闭，逐个打开对比效果。位置 `src/main/resources/application.yml` 的 `demo` 段：

| 开关 | 作用 | 对应 story |
| --- | --- | --- |
| `demo.sim-db-latency-ms` | 每条 SQL 前注入延迟，放大 N+1 累积效应（设 0 关闭） | 基线 |
| `demo.optimize.batch-query` | N+1 改批量查询 | US-010 |
| `demo.optimize.hikari-tuning` | HikariCP 连接池调优 | US-011 |
| `demo.optimize.cache` | Redis 缓存热点商品 | US-012 / US-013 |
| `demo.optimize.async` | 非核心逻辑 `@Async` 异步化 | US-014 |
| `demo.optimize.mq` | RabbitMQ 替代 `@Async` 削峰解耦 | US-015 |

## 学习路线与文档（阶段一）

| 主题 | 用户故事 | 文档 |
| --- | --- | --- |
| 诊断工具 Arthas（手动演示） | US-009 | [docs/ARTHAS.md](docs/ARTHAS.md) |
| HikariCP 连接池调优 | US-011 | [docs/HIKARICP.md](docs/HIKARICP.md) |
| Redis 缓存与缓存一致性方案对比 | US-012 / US-013 | [docs/CACHE-CONSISTENCY.md](docs/CACHE-CONSISTENCY.md) |
| 非核心逻辑异步化（@Async） | US-014 | [docs/ASYNC.md](docs/ASYNC.md) |
| RabbitMQ 替代 @Async 削峰解耦 | US-015 | [docs/MQ.md](docs/MQ.md) |
| 阶段一汇总对比 | US-016 | [docs/STAGE1-SUMMARY.md](docs/STAGE1-SUMMARY.md) |

主线：N+1 → 连接池 → 缓存 → 异步 → 消息，均以 feature flag 开关，配 JMeter 基线压测前后对比。

## 目录结构

```
concurrency-demo/
├── src/                    # 单体应用源码与配置
├── docker/                 # docker-compose 初始化脚本（MySQL init SQL 等）
├── docker-compose.yml      # 基础设施编排
├── docs/                   # 各主题教程文档
├── jmeter/                 # 压测脚本
└── ralph/prd.json          # 需求与验收（US-001 ~ US-023）
```
