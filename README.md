# concurrency-demo（微服务阶段）

> 本分支 `microservices` 是项目的**阶段二：微服务与分布式事务**（US-017 ~ US-023）。
> 阶段一的**单体应用与性能调优**（N+1、连接池、缓存、异步、消息）在 **`main` 分支**，见该分支的 README。

把阶段一的单体拆成微服务，暴露出分布式事务缺口，再用两条**互不替代**的路径补齐：强一致（Seata）与最终一致（可靠消息），最后做选型对比。

## 技术栈

- Java 21、Spring Boot 3.3.4、Maven 多模块
- Spring Cloud + Spring Cloud Alibaba：Nacos（注册/发现）、Gateway、OpenFeign、LoadBalancer
- MyBatis-Plus、MySQL 8、Redis 7、RabbitMQ 3.13、Seata 2.1（AT 模式）
- 压测 JMeter

## 模块

| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `gateway-service` | 8090 | Spring Cloud Gateway 统一入口 |
| `product-service` | 8071 | 商品服务：库存扣减、热点缓存、横向扩容验证 |
| `order-service` | 8072 | 订单服务：Feign 调商品，承载 Seata / 可靠消息两种一致性方案 |
| `concurrency-monolith` | 8080 | 阶段一单体（随分支保留，调优叙事见 `main` 分支） |

## 基础设施（docker-compose）

| 服务 | 端口 | 备注 |
| --- | --- | --- |
| MySQL | 3306 | 启动时执行 `docker/mysql/init` 建表与种子数据 |
| Nacos | 8848 / 9848 | 8848 控制台+HTTP，9848 gRPC（2.x 必需，否则注册超时） |
| RabbitMQ | 5672 / 15672 | 15672 管理台 `guest/guest` |
| Seata | 8091 / 7091 | 8091 TC（客户端直连），7091 控制台 `seata/seata` |

```bash
# 联调前先确认四容器均 healthy
docker compose up -d mysql nacos rabbitmq seata
```

## 如何运行

> 本机用 Java 21，例如 `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`

```bash
# 确认 mysql / nacos / rabbitmq / seata 四容器 healthy 后依次启动
mvn -pl product-service spring-boot:run               # 8071
mvn -pl order-service   spring-boot:run               # 8072
mvn -pl gateway-service spring-boot:run               # 8090，统一入口
```

经网关访问，例如可靠消息下单（最终一致）：
```bash
curl -XPOST localhost:8090/order-service/orders/reliable \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3}'
```

强一致下单（Seata，`mockFail=true` 触发全局回滚）：
```bash
curl -XPOST localhost:8090/order-service/orders \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3}'
```

整体编译：`mvn -DskipTests compile`（各模块均应 BUILD SUCCESS）。

## 学习路线与文档（阶段二）

| 主题 | 用户故事 | 文档 |
| --- | --- | --- |
| Nacos + Gateway + 服务拆分 + Feign（暴露分布式事务缺口） | US-017 ~ US-019 | [docs/MICROSERVICES.md](docs/MICROSERVICES.md) |
| 热点服务横向扩容与压测对比 | US-020 | [docs/SCALING.md](docs/SCALING.md) |
| 分布式事务·强一致（Seata AT） | US-021 | [docs/SEATA.md](docs/SEATA.md) |
| 分布式事务·最终一致（本地消息表 + RabbitMQ + 消费端幂等） | US-022 | [docs/RELIABLE-MESSAGE.md](docs/RELIABLE-MESSAGE.md) |
| **强一致 vs 最终一致选型对比** | US-023 | [docs/CONSISTENCY-TRADEOFF.md](docs/CONSISTENCY-TRADEOFF.md) |

> 阶段一（单体调优）的文档同样保留在本分支 `docs/` 下，完整叙事与运行方式见 `main` 分支 README。

## 目录结构

```
concurrency-demo/
├── gateway-service/        # 网关
├── product-service/        # 商品服务
├── order-service/          # 订单服务
├── concurrency-monolith/   # 阶段一单体（保留）
├── docker/                 # docker-compose 初始化脚本（MySQL init SQL 等）
├── docker-compose.yml      # 基础设施编排
├── docs/                   # 各主题教程文档
├── jmeter/                 # 压测脚本
└── ralph/prd.json          # 需求与验收（US-001 ~ US-023）
```
