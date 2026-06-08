# US-015 RabbitMQ 替代 @Async 承接下单非核心任务

## 为什么用 MQ 替代 @Async

US-014 的 `@Async` 把短信/邮件/积分丢进**进程内**线程池，下单主线程立即返回。但它有两个硬伤：

- **进程内线程池重启即丢任务**：应用崩溃/发布重启时，队列里还没跑的任务直接消失，没有补偿。
- **削峰能力有限**：队列容量满 + 线程达上限后触发拒绝策略（CallerRunsPolicy 会退化回同步拖慢下单，AbortPolicy 直接丢任务）。

当任务量大、或任务**不允许丢失**时，改用 RabbitMQ：任务被**持久化到 broker**，由独立消费者异步处理；应用重启不丢，broker 还能天然削峰。

## 开关与代码结构

`demo.optimize.mq=true` 时启用（默认 false，基线无需 RabbitMQ 即可启动）。相关 Bean 全部用
`@ConditionalOnProperty(name = "demo.optimize.mq", havingValue = "true")` 门控，关闭时不加载、不连 broker。

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| 队列/交换机/绑定/Template | `config/RabbitConfig.java` | 持久化 queue/exchange、JSON 转换器、带 confirm/returns 回调的 RabbitTemplate |
| 投递方 | `service/OrderMqPublisher.java` | `@TransactionalEventListener(AFTER_COMMIT)` 在事务提交后投递 `OrderPlacedEvent` |
| 消费方 | `service/OrderTaskConsumer.java` | `@RabbitListener` 手动 ack + Redis 幂等，跑 sms/email/points |
| 分流 | `service/OrderService.placeOrder` | `mq \|\| async` 时发布事件；mq 优先级高于 async |

### 与 @Async 共用同一个事件，靠 condition 互斥

下单时 `mq=true` 或 `async=true` 都发布 `OrderPlacedEvent`。两个 `@TransactionalEventListener` 监听同一事件，用 SpEL `condition` 互斥，避免重复处理：

- `OrderMqPublisher`：只在 mq=true 时作为 Bean 存在（投递到 MQ）。
- `NonCoreTaskService.onOrderPlaced`：`condition = "!@optimizeProperties.mq"`，mq 开启时让位给 MQ。

事件都在 `AFTER_COMMIT` 阶段消费——核心事务回滚则事件不投递，从根上避免「提交前/回滚后误发通知」。

## 消息丢失保障：三层防线

一条消息从「生产者 → broker → 消费者」全程可能丢，对应三道保障，缺一不可：

| 环节 | 风险 | 保障 | 本项目实现 |
| --- | --- | --- | --- |
| 生产者 → broker | 网络抖动，以为发了其实没到 | **publisher confirm** | `application.yml: publisher-confirm-type=correlated` + RabbitTemplate `ConfirmCallback`，broker 落账后回调 `[MQ-CONFIRM]` |
| 到了 broker 但无队列可路由 | 消息被默默丢弃 | **mandatory + return** | `setMandatory(true)` + `publisher-returns=true` + `ReturnsCallback`，不可路由时 `[MQ-RETURN]` 告警 |
| broker 自身重启 | 内存中的队列/消息丢失 | **持久化** | queue/exchange `durable=true`；JSON 消息默认 `PERSISTENT` 投递模式，落盘 |
| broker → 消费者 | 消费中崩溃，消息已删 | **手动 ack** | `listener.simple.acknowledge-mode=manual`，业务成功才 `basicAck`；未 ack 的消息 broker 会重新投递 |

> 注意：三者要**同时成立**才不丢。比如只开手动 ack 但队列非持久化，broker 重启照样丢；只持久化但用自动 ack，消费中崩溃也丢。

### 失败处理：nack 不重回队列

消费抛异常时 `basicNack(tag, requeue=false)`：不重回队列，避免「毒消息」（必然失败的消息）无限重投打满 CPU。生产环境应给队列配**死信交换机（DLX）**，把失败消息转入死信队列（DLQ）人工排查/补偿，而不是直接丢弃。

## 幂等性设计

手动 ack + 重投 + 网络抖动决定了**同一条消息可能被消费多次**（at-least-once 语义）。因此消费端必须幂等，否则会重复发短信、重复加积分。

本项目以 `orderNo` 为幂等键，消费前用 Redis `SETNX` 占位：

```java
Boolean first = redisTemplate.opsForValue()
        .setIfAbsent("order:processed:" + event.orderNo(), "1", Duration.ofHours(1));
if (Boolean.FALSE.equals(first)) {   // 已处理过
    channel.basicAck(deliveryTag, false);   // 直接 ack 跳过
    return;
}
```

- `correlationId` / 消息体里的 `orderNo` 在重投时保持不变，是天然的幂等键。
- 生产环境更稳妥的做法：幂等键落库（唯一索引）或「业务状态机 + 乐观锁」，Redis 键只作快速短路；TTL 要覆盖最大重试窗口。

## 实测验证

启动（mq=true，避开 8080 用 8081）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  mvn -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8081 --demo.optimize.mq=true --demo.sim-db-latency-ms=0"

curl -s -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":1}'
```

观察到的链路（日志）：

```
[nio-8081-exec-1] OrderMqPublisher : [MQ] 事务已提交，非核心任务投递到 RabbitMQ orderNo=ORD...
[nectionFactory2] RabbitConfig     : [MQ-CONFIRM] broker 已确认收下消息 correlationId=ORD...
[ntContainer#0-1] OrderTaskConsumer: [MQ-CONSUME] 收到非核心任务 orderNo=ORD...，开始处理
[ntContainer#0-1] SmsService       : [SMS] ...
[ntContainer#0-1] EmailService     : [EMAIL] ...
[ntContainer#0-1] PointsService    : [POINTS] ...
[ntContainer#0-1] OrderTaskConsumer: [MQ-CONSUME] orderNo=ORD... 处理完成并已 ack
```

- 下单响应 `elapsedMs=65`：主线程在 Tomcat 线程立即返回，非核心任务在 `ntContainer#0-*` 消费线程异步执行（与 @Async 一样不阻塞下单）。
- `publisher confirm` 生效：`[MQ-CONFIRM]` 回调打印 broker 已落账。
- 持久化：`rabbitmqctl list_queues name durable messages` → `order.non-core.queue  true  0`（durable=true，消费 ack 后 messages 归零）。
- 幂等：消费后 Redis 出现 `order:processed:ORD...` 键。

## 与 @Async（US-014）对比

| 维度 | @Async（US-014） | RabbitMQ（US-015） |
| --- | --- | --- |
| 任务存放 | 进程内线程池队列 | broker，持久化落盘 |
| 应用重启 | 未跑完的任务丢失 | 不丢，重启后消费者继续消费 |
| 削峰 | 队列满即触发拒绝策略 | broker 缓冲，消费者按自身能力拉取 |
| 投递语义 | 至多一次（可能丢） | 至少一次（手动 ack + 重投）→ 需幂等 |
| 复杂度 | 低，一个线程池 | 高，需 broker、confirm/ack、幂等、DLQ |
| 适用 | 任务可丢、量不大 | 任务不可丢、量大、需削峰 |
