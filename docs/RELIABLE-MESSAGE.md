# RabbitMQ 可靠消息：最终一致（US-022）

## 1. 要解决的问题（与 US-021 同一个缺口，不同解法）

US-019 跨服务下单留下的一致性缺口（扣库存在 product 本地事务提交、建单在 order 本地事务，本地
`@Transactional` 管不到对方）——US-021 用 **Seata AT 强一致**补上：调用返回即一致，代价是全局锁、
同步阻塞、下游必须可用。

US-022 换一条路：**用可靠消息做最终一致**。下单不再同步等扣库存，而是先落订单（待确认）+ 一条
「扣库存消息」，立即返回；扣库存经 RabbitMQ 异步完成后回传结果，再把订单确认或取消。
**牺牲「即时一致」换取「高吞吐 + 服务解耦 + 下游可短暂不可用」**。

## 2. 两个核心难点与对策

可靠消息要落地，必须同时解决「消息会不会丢」和「消息会不会重复」：

| 难点 | 场景 | 对策 |
| --- | --- | --- |
| 消息丢失（双写缺口） | 订单写库成功，但发消息前进程崩溃 → 库存永远不扣 | **本地消息表（transactional outbox）**：订单与消息写在同一本地事务，要么都成功要么都回滚；再由轮询器可靠投递 |
| 消息重复 | 投递「至少一次」、轮询重发、消费重试 | **消费端幂等**：以 `order_no` 为主键先占位，主键冲突=已处理过，直接跳过扣减 |

三层防丢沿用 US-015：① 本地消息表（不丢待发消息）② publisher confirm + 队列/消息持久化（broker
确认收下才置 SENT）③ 手动 ack（消费成功才确认）。

## 3. 端到端流程

```
order-service                         RabbitMQ                    product-service
─────────────                         ────────                    ───────────────
POST /orders/reliable
 └─[本地事务] insert t_order(status=待确认)
            + insert t_order_outbox(status=NEW)   ← 同一事务，杜绝双写缺口
 └─立即返回 orderNo（待确认）

OutboxRelay @Scheduled 每2s
 └─扫描 NEW → convertAndSend ──────▶ stock.deduct.queue ──▶ StockDeductListener
                              ◀── publisher confirm           └─[本地事务] insert 幂等表(order_no PK)
 └─收到 confirm → outbox 置 SENT                                   主键冲突→已处理→跳过
                                                              └─扣库存(库存充足? 成功:失败)
 OrderResultListener ◀── order.result.queue ◀──────────────── └─回传 OrderResultMsg + basicAck
 └─[幂等]仅当订单仍=待确认才推进
    success → 已确认(1) ; 失败 → 已取消(2，补偿)
```

订单状态：`0=待确认` `1=已确认` `2=已取消（库存不足补偿）`。

## 4. 关键代码

| 关注点 | 位置 |
| --- | --- |
| 订单+消息同事务写入 | `order-service` `OrderService.placeOrderReliable` |
| 轮询投递 + confirm 置 SENT + 失败重发 | `order-service` `OutboxRelay`（`@EnableScheduling`，`fixedDelay=2000`） |
| 结果消费、状态推进（幂等） | `order-service` `OrderResultListener` + `OrderService.applyDeductResult` |
| 扣库存消费、幂等去重 | `product-service` `StockDeductListener` + `ProductService.deductForOrder` |
| 队列/交换机/DLQ 拓扑（两侧同名声明） | 两服务各自的 `config.RabbitConfig` |
| 本地消息表 / 幂等去重表 | `t_order_outbox` / `t_stock_deduct_log`（见 init SQL） |

> 跨模块消息用各自的同构 DTO（不共享类）。`Jackson2JsonMessageConverter` 的 type precedence 设为
> `INFERRED`：忽略发送方写入的 `__TypeId__` 头（那是对方包名下的类），按 `@RabbitListener` 方法参数类型反序列化。

## 5. 实测（经网关 `localhost:8090/order-service`）

环境：product(8071)+order(8072)+gateway(8090) 注册 Nacos；MySQL/RabbitMQ/Nacos 容器 healthy。
基线 `product_7` stock=999979。

**① 正常路径** `POST /orders/reliable {userId:1,productId:7,quantity:3}`
- 接口立即返回 `elapsedMs=146`，订单 `status=0`（待确认）；
- ~1.5s 后再查 `status=1`（已确认）；stock 999979→999976（-3）；outbox 该行 `status=1(SENT)`；幂等表新增一行。
- 日志链路：`[ORDER-REL]受理 → [OUTBOX]投递 → [OUTBOX]confirm置SENT → [STOCK-CONSUME]收到 → [STOCK]扣库存成功 → [STOCK-CONSUME]回传 → [ORDER-RESULT]success=true → 订单已确认`。

**② 补偿路径** 同请求 `quantity:2000000`（超库存）
- 立即返回 `status=0`；~1s 后 `status=2`（已取消）；stock **不变**（999976），库存本就没扣，无需冲正。
- 日志：`[STOCK]库存不足扣减失败 → 回传 success=false → [ORDER-REL]扣库存失败，订单补偿取消`。

**③ 幂等/重试路径** 直接向 `order.saga.exchange` 重投正常路径那条扣库存消息
- product 日志 `[STOCK]消息已处理过，幂等跳过`，stock **仍是 999976**（不重复扣）；
- order 日志 `[ORDER-RESULT]结果重复投递，订单已是终态，忽略 status=1`（状态推进幂等）。

## 6. 局限与取舍

- **最终一致而非强一致**：下单返回时库存尚未扣，存在「订单待确认」窗口；超卖兜底靠扣库存时的
  `stock >= quantity` 乐观判断，不靠下单时锁定。对「下单即扣减锁定」的强诉求要用 Seata（US-021）。
- **补偿的简化**：本演示库存「不足就不扣」，失败时无需反向冲正；若改成「先预扣再确认」，取消时需发反向消息把库存加回（TCC/Saga 补偿），复杂度更高。
- **毒消息**：扣库存消费异常时 `basicNack(requeue=false)` 进 `stock.deduct.dlq`，避免无限重投；真正的「不丢」由本地消息表轮询重发保证，DLQ 仅供排查/人工补偿。
- **幂等表无界增长**：`t_stock_deduct_log` 需定期归档；生产可用带 TTL 的存储或与业务表合并判断。

详尽的「强一致 vs 最终一致」选型对比见 US-023（`docs/` 待补）。
