拿一个最普通的 Spring Boot 项目。后台管理系统，几张表的增删改查，日活可能三位数。默认配置启动，Tomcat 200 个线程，HikariCP 10 个数据库连接，啥也没调过。能跑，没人投诉。

现在打开 JMeter，200 个线程同时请求你的订单列表接口。

jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/
报告出来了：平均响应时间 4200ms，吞吐量 38 req/s，错误率 12%。四秒多才返回一个列表，一秒才处理 38 个请求，还有八分之一直接报错了。

这就是你的”高并发经验”的起点。

先找谁在拖后腿
大部分人看到这个结果，第一反应是调大 Tomcat 线程数。这是错的。线程数不是瓶颈的时候，调到 2000 也没用——线程全卡在别的地方排队呢。

用 Arthas 看一下这个接口内部每一步的耗时：

java -jar arthas-boot.jar

trace com.example.OrderController listOrders
输出会告诉你 listOrders 里面调了什么方法、每个方法花了多久。假设你看到的是这样：

+---[3856ms] com.example.OrderService.listOrders()
+---[3ms] com.example.OrderMapper.selectPage()
+---[3851ms] com.example.OrderService.fillOrderDetails()
3856 毫秒里有 3851 毫秒花在了 fillOrderDetails。翻开这个方法一看——订单列表查出来 20 条数据，然后 for 循环每一条去查用户信息、商品信息、物流信息。三个查询乘以 20 条，60 次数据库调用，每次几十毫秒。

这就是经典的 N+1 问题。一次列表查询变成了 61 次数据库查询。

改法很直接：把 for 循环里的单条查询改成批量查询。先收集所有 userId，一次 WHERE user_id IN (...) 查出来，再在内存里匹配。

List<Long> userIds = orders.stream()
.map(Order::getUserId)
.distinct()
.collect(Collectors.toList());
Map<Long, User> userMap = userService.listByIds(userIds)
.stream()
.collect(Collectors.toMap(User::getId, Function.identity()));

for (Order order : orders) {
order.setUserName(userMap.get(order.getUserId()).getName());
}
商品信息、物流信息同理。61 次查询变成 4 次。再跑一次 JMeter：平均响应时间从 4200ms 降到 320ms，吞吐量从 38 涨到 280 req/s。

这一步优化完，面试的时候你能聊什么？N+1 问题的识别和解决、批量查询的写法、IN 子句数量太大时需要分批（MySQL 默认没有硬限制，但超过几千个值查询计划可能不走索引）、MyBatis 的 <foreach> 标签怎么写。一个优化动作背后能展开一大片。

数据库连接池是第二个瓶颈
280 req/s 看起来不错了，但 JMeter 报告里有个数字不太对：TP99 响应时间 1800ms。平均 320ms 是因为大部分请求很快，但最慢的那 1% 要将近两秒。

再看 HikariCP 的日志，会发现大量这种输出：

HikariPool-1 - Connection is not available, request timed out after 30000ms
默认 10 个连接，200 个并发线程在抢。抢到连接的很快处理完了，没抢到的在等。等 30 秒还没等到就直接超时报错——之前那 12% 的错误率就是这么来的。

spring:
datasource:
hikari:
maximum-pool-size: 30
minimum-idle: 10
connection-timeout: 3000
连接数调到 30，超时时间从 30 秒改成 3 秒（等不到就快速失败，别让用户傻等）。再跑一次：错误率降到 0，TP99 从 1800ms 降到 450ms。

但连接池不是越大越好。HikariCP 官方有一篇 “About Pool Sizing”，结论让很多人意外：连接数 ≈ CPU 核心数 * 2 + 磁盘数。4 核机器大概 10~15 个连接就到头了。再多数据库那头反而更慢——每个连接要占内存、维护锁状态，连接太多数据库自己先撑不住。

这里面试能聊的东西：连接池的工作原理、为什么连接不是越多越好、connection-timeout 设太长会导致线程一直占着不释放、连接泄漏怎么排查（HikariCP 的 leak-detection-threshold 参数）。

有些查询根本不用走数据库
商品信息一天更新一次，但每次请求订单列表都去查商品表。200 个并发请求，200 次查同样的商品数据。

@Cacheable(value = "product", key = "#productId")
public Product getProduct(Long productId) {
return productMapper.selectById(productId);
}
加上 Redis 缓存之后，商品查询从数据库的几十毫秒变成 Redis 的 1~2 毫秒。吞吐量又上了一个台阶：380 req/s。

但缓存一加就有新问题了。运营改了商品价格，缓存里还是旧价格。用户看到的订单列表里显示的价格跟商品详情页不一样——这种 bug 比接口慢更要命。

最简单的处理：商品更新的时候删掉缓存。下次查的时候发现缓存没了，查数据库，回填缓存。

@CacheEvict(value = "product", key = "#product.id")
public void updateProduct(Product product) {
productMapper.updateById(product);
}
但如果更新操作和查询操作并发了呢？线程 A 删了缓存，线程 B 紧接着查，发现缓存没了，去查数据库（拿到的还是旧值因为线程 A 的数据库更新还没提交），回填了旧值。线程 A 的数据库更新提交了，但缓存里已经被线程 B 塞了旧值。

这就是缓存一致性问题。延迟双删、订阅 binlog、设置较短的过期时间兜底——每种方案都有适用场景和局限性。面试聊到这里，已经不是”背八股文”了，是在讨论工程决策。

接口里的非核心逻辑
订单列表接口优化到这一步，已经够快了。但你翻了翻其他接口，发现下单接口有个问题：扣库存、创建订单、发短信通知、发邮件确认、加积分，全在一个同步方法里。

扣库存和创建订单必须同步——这是核心逻辑，失败了要回滚。但发短信要 500ms，发邮件要 300ms，加积分又要查一次数据库。用户点”下单”之后要等一秒多才看到结果，其中大半的时间花在了他根本不关心的事情上。

@Async("orderExecutor")
public void handlePostOrder(Long orderId) {
smsService.send(...);
emailService.send(...);
pointsService.add(...);
}
非核心逻辑丢到异步线程池里。下单接口只做扣库存+创建订单，200ms 内返回。用户体验好了，Tomcat 线程占用时间也短了，能接更多请求。

如果非核心任务量大或者不能丢，再往前一步——用消息队列替代 @Async。RabbitMQ 或 Kafka 把任务丢进队列，消费者慢慢处理。队列有持久化，服务重启了消息也不会丢。

面试聊到这里又能展开：@Async 的线程池溢出了怎么办（CallerRunsPolicy 还是直接拒绝）、消息队列的消息丢失怎么保证（publisher confirm + 持久化 + 手动 ack）、消息重复消费怎么处理（幂等性设计）。

回头看一眼数字
最初：38 req/s，4200ms 响应，12% 错误率。

改完 N+1、调了连接池、加了缓存、异步化之后：500+ req/s，平均响应 80ms，错误率 0。

整个过程用到的技术：批量查询、连接池调优、Redis 缓存、异步线程池。没有分布式锁，没有消息队列集群，没有分库分表，没有 K8s 自动扩容。全是最基础的优化手段，在一台开发机上就能跑通。

但你把这个过程在面试里从头讲一遍——每一步为什么这么做、发现了什么问题、用什么工具诊断的、改完效果怎么样——面试官听到的不是”背了多少知识点”，而是”这个人碰到性能问题知道怎么一步步排查和解决”。

这就是面试官想要的”高并发经验”。不是你的系统扛过多少 QPS，而是你有没有从”慢”到”快”完整走过一遍，知道每一步的原因和代价。

百万 QPS 的系统，全国做过的人可能一个会议室就坐满了。面试官自己大概率也没扛过。他想招的不是那种人，他想招的是给一个慢系统能把它搞快的人。你手头这个日活三位数的 CRUD 项目，压测一轮、优化一轮，就是一份完整的高并发实战经历。


