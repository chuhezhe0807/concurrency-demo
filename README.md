# concurrency-demo

一个循序渐进的**后端性能与分布式实战**学习项目：从一个有意写「慢」的单体应用出发，先在单体内逐项调优（N+1、连接池、缓存、异步、消息），再拆成微服务，最后补齐分布式事务的两种解法并做选型对比。每一步都配一篇文档讲清「为什么这么做、代价在哪」。

## 技术栈

- Java 21、Spring Boot 3.3.4、Maven 多模块
- Spring Cloud + Spring Cloud Alibaba（Nacos 注册/发现、Gateway、OpenFeign、LoadBalancer）
- MyBatis-Plus、MySQL 8、Redis 7、RabbitMQ 3.13、Seata 2.1（AT 模式）
- 压测 JMeter，诊断 Arthas

## 模块

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `concurrency-monolith` | 8080 | 阶段一单体：性能调优全部在这里演示，特性用 feature flag 开关 |
| `gateway-service` | 8090 | 阶段二 Spring Cloud Gateway 统一入口 |
| `product-service` | 8071 | 阶段二商品服务（含库存扣减、热点缓存） |
| `order-service` | 8072 | 阶段二订单服务（Feign 调商品，承载 Seata/可靠消息两种一致性方案） |

## 基础设施（docker-compose）

| 服务 | 端口 | 备注 |
| --- | --- | --- |
| MySQL | 3306 | 启动时执行 `docker/mysql/init` 建表与种子数据 |
| Redis | 6380 → 6379 | 宿主机 6380，容器内 6379 |
| RabbitMQ | 5672 / 15672 | 15672 管理台 `guest/guest` |
| Nacos | 8848 / 9848 | 8848 控制台+HTTP，9848 gRPC（2.x 必需） |
| Seata | 8091 / 7091 | 8091 TC，7091 控制台 `seata/seata` |

```bash
# 按阶段需要拉起对应容器（不必全开）
docker compose up -d mysql redis rabbitmq          # 阶段一
docker compose up -d mysql nacos rabbitmq seata    # 阶段二
```

## 如何运行

> 本机用 Java 21。示例：`export JAVA_HOME=/opt/homebrew/opt/openjdk@21`

**阶段一（单体）**
```bash
docker compose up -d mysql redis rabbitmq
mvn -pl concurrency-monolith spring-boot:run          # http://localhost:8080
```
各项优化通过 `application.yml` 里的 feature flag 开关，压测前后对比即可，细节见各篇文档。

**阶段二（微服务）**

先确认 `mysql / nacos / rabbitmq / seata` 四容器均 healthy，再依次启动：
```bash
mvn -pl product-service spring-boot:run               # 8071
mvn -pl order-service   spring-boot:run               # 8072
mvn -pl gateway-service spring-boot:run               # 8090，统一入口
```
经网关访问，例如可靠消息下单：
```bash
curl -XPOST localhost:8090/order-service/orders/reliable \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3}'
```

**整体编译**
```bash
mvn -DskipTests compile      # 4 个模块均应 BUILD SUCCESS
```

## 学习路线与文档

每篇文档对应一组用户故事（US），讲清问题、做法与取舍。

### 阶段一：单体性能调优（US-001 ~ US-016）

| 主题 | 文档 |
| --- | --- |
| 诊断工具 Arthas（手动演示） | [docs/ARTHAS.md](docs/ARTHAS.md) |
| HikariCP 连接池调优 | [docs/HIKARICP.md](docs/HIKARICP.md) |
| Redis 缓存热点商品与一致性方案 | [docs/CACHE-CONSISTENCY.md](docs/CACHE-CONSISTENCY.md) |
| 非核心逻辑异步化（@Async） | [docs/ASYNC.md](docs/ASYNC.md) |
| RabbitMQ 替代 @Async 削峰解耦 | [docs/MQ.md](docs/MQ.md) |
| 阶段一汇总对比 | [docs/STAGE1-SUMMARY.md](docs/STAGE1-SUMMARY.md) |

主线：N+1 批量查询、连接池、缓存、异步、消息——均以 feature flag 开关，配 JMeter 基线压测对比。

### 阶段二：微服务与分布式事务（US-017 ~ US-023）

| 主题 | 文档 |
| --- | --- |
| Nacos + Gateway + 服务拆分 + Feign | [docs/MICROSERVICES.md](docs/MICROSERVICES.md) |
| 热点服务横向扩容与压测对比 | [docs/SCALING.md](docs/SCALING.md) |
| 分布式事务·强一致（Seata AT） | [docs/SEATA.md](docs/SEATA.md) |
| 分布式事务·最终一致（本地消息表 + RabbitMQ） | [docs/RELIABLE-MESSAGE.md](docs/RELIABLE-MESSAGE.md) |
| **强一致 vs 最终一致选型对比** | [docs/CONSISTENCY-TRADEOFF.md](docs/CONSISTENCY-TRADEOFF.md) |

主线：拆服务后暴露分布式事务缺口（US-019），用 Seata 强一致与可靠消息最终一致两条**互不替代**的路径补上，最后做选型对比。

## 目录结构

```
concurrency-demo/
├── concurrency-monolith/   # 阶段一单体
├── gateway-service/        # 阶段二网关
├── product-service/        # 阶段二商品服务
├── order-service/          # 阶段二订单服务
├── docker/                 # docker-compose 初始化脚本（MySQL init SQL 等）
├── docker-compose.yml      # 基础设施编排
├── docs/                   # 各主题教程文档
├── jmeter/                 # 压测脚本
└── ralph/prd.json          # 需求与验收（US-001 ~ US-023）
```
