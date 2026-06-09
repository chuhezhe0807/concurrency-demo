# 阶段二：微服务拆分边界与引入的新问题（US-017 ~ US-019）

阶段一是一个单体（concurrency-monolith）。阶段二把它按业务边界拆成多个独立服务，前面用网关统一入口、
用 Nacos 做注册发现。这份文档说明**怎么拆的（边界）**，以及拆完之后**单体没有、微服务才有的新问题**。

## 1. 当前拓扑

```
            ┌─────────────────┐
client ───▶ │ gateway-service │  :8090  Spring Cloud Gateway（响应式）
            └────────┬────────┘  按 serviceId 路由：/<service-id>/** → lb://<service-id>
                     │ (lb://，Spring Cloud LoadBalancer 选实例)
        ┌────────────┴────────────┐
        ▼                         ▼
┌────────────────┐  Feign   ┌────────────────┐
│ order-service  │ ───────▶ │ product-service│
│ :8072  t_order │  扣库存   │ :8071 t_product│
└───────┬────────┘          └───────┬────────┘
        │                           │
        └──────────┬────────────────┘
                   ▼
              MySQL (concurrency_demo)
   ┌─────────────────────────────────────┐
   │ 所有服务都注册到 Nacos :8848（注册中心）│
   └─────────────────────────────────────┘
```

| 服务 | 端口 | 职责 | 数据 |
| --- | --- | --- | --- |
| gateway-service | 8090 | 统一入口、按 serviceId 路由、负载均衡 | 无 |
| product-service | 8071 | 商品查询/改价/扣库存 | t_product |
| order-service | 8072 | 下单（经 Feign 扣库存 + 建订单） | t_order |
| concurrency-monolith | 8080 | 阶段一单体，保留作对照 | 全部表 |

## 2. 拆分边界怎么定的

按**业务能力 + 数据所有权**拆，而不是按技术分层：

- **product-service 拥有 t_product**：商品的查询、改价、库存扣减都在它内部完成。别的服务**不直接读写 t_product**，只能通过它的接口。
- **order-service 拥有 t_order**：负责订单生命周期。下单需要的库存扣减不属于它，于是**跨服务调用** product-service。
- **一个服务一份数据所有权**：这是微服务的关键纪律。如果 order-service 直接连 t_product 改库存，就等于共享数据库、拆了等于没拆——任何一方改表结构都会互相打架。

> 本项目 demo 里几个服务连的是**同一个 MySQL 实例的同一个库**（省去多库运维）。但代码层面严守
> 「只有 product-service 碰 t_product」的边界——逻辑上各自独立，真要分库只是改连接串的事。

## 3. 引入的新问题

单体里「下单扣库存」就是同一个事务里的两条 SQL，简单、可靠。拆开后，同样一件事变成了跨进程协作，
冒出一串单体不存在的问题：

### 3.1 网络调用：慢且不可靠

本地方法调用是纳秒级、不会"失败"；跨服务的 Feign 调用是一次完整的 HTTP 往返，会**超时、抖动、对端宕机**。

- 实测下单 `elapsedMs≈165ms`，比单体的本地调用慢一个量级（多了服务发现 + HTTP 序列化 + 网络往返）。
- 需要考虑**超时、重试、熔断/降级**（Feign 可配 connectTimeout/readTimeout，配合 Sentinel/Resilience4j 做熔断）——否则一个慢服务会拖垮调用方线程池（雪崩）。
- 重试要小心**幂等**：扣库存重试两次可能扣两次。

### 3.2 数据一致性：本地事务管不到远程（核心问题）

order-service 的 `placeOrder` 标了 `@Transactional`，但这个事务**只能回滚本地 t_order 的写**，
管不到 product-service 已经提交的库存扣减：

```
1. Feign 调 product-service 扣库存  → product 库已 commit（库存 -3）
2. order-service 建订单 insert       → 若这一步抛异常/宕机
3. 本地事务回滚                       → t_order 没了，但第 1 步的库存扣减回不来
                                       结果：库存被"扣空了却没有对应订单"
```

这就是分布式事务问题。单体里步骤 1、2 在同一个本地事务里，要么全成功要么全回滚；拆开后没有了这个保证。

**本 story（US-019）只暴露问题，不解决**。两条主流解法分别在后面：

| 方案 | 一致性 | 思路 | 对应 story |
| --- | --- | --- | --- |
| Seata（AT/TCC） | 强一致 | 全局事务协调器，跨服务统一提交/回滚 | US-021（[SEATA.md](SEATA.md)） |
| 可靠消息（本地消息表 / publisher confirm + 手动 ack） | 最终一致 | 先保证消息不丢，靠重试/补偿最终对齐 | US-022（[RELIABLE-MESSAGE.md](RELIABLE-MESSAGE.md)） |

两种方案的适用场景、性能代价、复杂度与选型建议对比见 [CONSISTENCY-TRADEOFF.md](CONSISTENCY-TRADEOFF.md)（US-023）。

代码里 `OrderService.placeOrder` 第 3 步上方有注释明确标注了这个回滚缺口。

### 3.3 其它连带问题（后续 story 涉及）

- **服务发现与负载均衡**：调用方不再写死 IP，而是按服务名经 Nacos + LoadBalancer 找实例（US-017 已落地；US-020 横向扩容验证多实例分发）。
- **配置分散**：每个服务一份 application.yml，后续可上 Nacos 配置中心统一管理。
- **可观测性**：一次下单跨多个服务，需要链路追踪（traceId）才能排查问题。

## 4. 复现：经网关跑通跨服务下单

```bash
export JH=/opt/homebrew/opt/openjdk@21
# 依赖：docker mysql + nacos
docker compose up -d mysql nacos

# 三个服务各起一个（也可分别 cd 到模块跑）
JAVA_HOME=$JH mvn -pl product-service spring-boot:run &
JAVA_HOME=$JH mvn -pl order-service   spring-boot:run &
JAVA_HOME=$JH mvn -pl gateway-service spring-boot:run &

# 经网关跨服务下单：网关 → order-service →(Feign)→ product-service 扣库存
curl -X POST http://localhost:8090/order-service/orders \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3}'
# => {"orderId":...,"orderNo":"ORD...","elapsedMs":165}

# 验证库存确实被扣（经网关查 product-service）
curl http://localhost:8090/product-service/products/7
```

实测：下单成功后 t_product 库存按 quantity 递减；order-service 日志打印
`[ORDER] 跨服务下单成功 orderNo=... amount=53.97`（17.99×3），Feign 日志显示
`For 'product-service' URL not provided. Will try picking an instance via load-balancing.`，
印证扣库存是经服务发现 + 负载均衡的跨服务调用完成的。库存不足时下单中止（接口返回错误）。
