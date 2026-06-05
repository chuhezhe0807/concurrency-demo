# 非核心逻辑异步化 @Async（US-014）

下单的核心是「扣库存 + 创建订单」，短信/邮件/积分是非核心。基线（US-006）把它们全同步执行，
用户要等 500+300+100ms 才拿到响应。本阶段用 `demo.optimize.async=true` 把非核心逻辑丢进线程池，
下单主线程不等它跑完即返回。

## 开关与代码

- `config/AsyncConfig`：`@EnableAsync` + 自定义线程池 `orderExecutor`。
- `event/OrderPlacedEvent`：下单成功事件，在下单事务里发布。
- `service/NonCoreTaskService.onOrderPlaced`：`@TransactionalEventListener(AFTER_COMMIT)` + `@Async("orderExecutor")`，
  内部依次跑 sms/email/points。单独成 bean 是因为 `@Async` 靠 Spring 代理生效，**同一个 bean 内部自调用不会异步**。
- `OrderService.placeOrder`：`async=true` 时 `publishEvent(OrderPlacedEvent)` 后立即返回；
  `false` 时保持同步（US-006 行为）。

### 为什么用事务事件而不是直接 dispatch

非核心任务**必须在核心事务提交成功之后**才执行。若像最初那样在事务内直接 `@Async` dispatch，
任务会在提交前就在另一个线程跑起来——一旦核心随后回滚，就发生了「订单没成功、短信却已发出」。
改用 `@TransactionalEventListener(AFTER_COMMIT)`：事件在事务里发布，但监听器被推迟到**提交后**才触发，
核心回滚则监听器根本不会执行。

实测：

```
# 正常下单：提交后才异步发通知
{"orderId":...,"elapsedMs":48}
[order-async-1] [ASYNC] 事务已提交，非核心任务进入线程池执行 orderNo=ORD...
[order-async-1] [SMS] / [EMAIL] / [POINTS] ...

# 失败下单（库存不足）：核心回滚 → 无任何通知
curl ... -d '{"userId":1,"productId":7,"quantity":999999999}'  → 500
（日志中无 [ASYNC]/[SMS]：回滚未触发任何通知）
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  mvn -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --demo.optimize.async=true"

curl -s -X POST "http://localhost:8080/orders" \
  -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":1}'; echo
```

## 响应时间对比（单请求 elapsedMs，sim-db-latency=0）

| 开关 | 第1次(预热) | 稳定 | 说明 |
|------|------------|------|------|
| async=false（同步） | 953 ms | ~912~922 ms | 含 sms 500 + email 300 + points 100 |
| async=true（异步） | 64 ms | ~3~5 ms | 主线程只做扣库存+建单，非核心丢线程池 |

异步下日志显示非核心任务在 `order-async-N` 线程执行，主线程早已返回：

```
[order-async-1] [SMS] 已向用户 1 发送订单 ... 的短信通知
[order-async-1] [EMAIL] ...
[order-async-1] [POINTS] ...
```

## 线程池参数

`orderExecutor`（见 `AsyncConfig`）：

| 参数 | 值 | 说明 |
|------|----|------|
| corePoolSize | 4 | 常驻线程 |
| maxPoolSize | 8 | 队列满后才扩到的上限 |
| queueCapacity | 100 | 核心线程忙时任务先入队 |
| threadNamePrefix | order-async- | 便于日志定位 |
| rejectedExecutionHandler | CallerRunsPolicy | 见下 |

提交顺序（ThreadPoolExecutor 语义）：先占满 core(4) → 再入队(100) → 队列满才扩到 max(8) → 仍满则触发拒绝策略。

## 拒绝策略：CallerRunsPolicy vs 直接拒绝

当「线程到 max 且队列已满」，新任务无处安放，由拒绝策略决定怎么办：

| 策略 | 行为 | 适用 / 代价 |
|------|------|-------------|
| **CallerRunsPolicy**（本项目选用） | 由**提交任务的线程**（这里是处理下单的 Tomcat 线程）自己跑该任务 | 不丢任务；调用方变慢→自然背压，减缓继续提交。代价：该次下单响应退化为同步、变慢 |
| **AbortPolicy**（JDK 默认） | 直接抛 `RejectedExecutionException` | 「宁可失败也不拖慢」；需上层兜底/重试，否则任务丢失 |
| DiscardPolicy / DiscardOldestPolicy | 静默丢弃新/最老任务 | 可容忍丢任务的场景，少用 |

选 CallerRunsPolicy 的理由：非核心任务不希望丢，且让调用方“帮忙跑”能形成背压、保护下游。

## 局限与注意

- 触发时机已用 `AFTER_COMMIT` 修正：核心回滚不会误发通知（见上）。
- **失败兜底仍缺失**：非核心任务在提交后执行，此时核心已成功、不能再回滚核心（不能因短信没发就取消订单）。
  方向是「让它最终成功」而非回滚——但当前 @Async 没有重试/持久化：
  - @Async void 异常只进日志，没人重试；
  - 线程池是**进程内**的，应用重启会丢掉未执行完的任务。
- 任务量大或「不能丢」时应换消息队列（US-015）：MQ 持久化 + 手动 ack + 消费端重试 + 死信兜底 + 幂等，
  把「不丢 + 重试 + 幂等」一起解决。更稳的还可用「本地消息表」（核心事务里同库写一条待办记录，保证原子）。
