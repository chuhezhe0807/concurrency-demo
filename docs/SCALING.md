# 横向扩容：热点服务多实例 + 网关负载均衡（US-020）

阶段二把商品服务（热点读）拆成了独立的 `product-service`（US-018）。本节演示对它做**横向扩容**
（多开几个实例），由网关经 Spring Cloud LoadBalancer 把请求轮询分发到多个实例，并用 JMeter 量化
「单实例 vs 多实例」的吞吐与响应时间差异，最后说明横向扩容的前提与瓶颈下移现象。

## 1. 为什么单实例会先到瓶颈

商品查询本身在本机是亚毫秒级，单机压测打不出瓶颈。为在单机上**可复现地**演示扩容收益，给
`ProductService.getById` 加了一个模拟处理耗时开关（代表慢下游 / 慢查询 / 远程调用等**阻塞型 I/O**）：

```java
// product-service ProductService.getById
@Value("${demo.product.process-latency-ms:0}")  // 默认 0 不生效
private long processLatencyMs;

public Product getById(Long id) {
    if (processLatencyMs > 0) Thread.sleep(processLatencyMs);   // 模拟阻塞 I/O
    return productMapper.selectById(id);
}
```

再把每个实例的 Tomcat 工作线程数限制到 50（`server.tomcat.threads.max=50`）。这样**单实例的吞吐天花板**就被钉死：

```
单实例吞吐上限 ≈ 工作线程数 / 单请求处理时间 = 50 / 0.1s ≈ 500 req/s
```

线程在 `sleep` 期间被占住，无法处理新请求。客户端再加并发也没用——多出来的请求只能在队列里排队，
表现为**吞吐不再上升、响应时间线性变长**（这正是 US-009 里「线程阻塞在 I/O 时，调大线程数无用」的同款现象，
只是这次瓶颈是单实例的处理能力，解法从「优化单点」换成「多开几个点」）。

## 2. 扩容方式：mvn 多进程，同服务名注册 Nacos

本项目阶段二全程用 `mvn spring-boot:run` 跑 Java 服务，扩容沿用同一方式（放弃 docker 多容器：
本机无 Java21 JRE 镜像且镜像拉取受限）。**多开几个 `product-service` 进程，各用不同 `--server.port`，
它们会以同一个 `spring.application.name=product-service` 注册到 Nacos**，网关侧的 Spring Cloud
LoadBalancer 拿到该服务名下的多个实例后自动轮询，无需任何代码或网络改造（进程都在宿主机，
注册的就是宿主机 IP + 各自端口）。

```bash
# 实例 1（8071）
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl product-service spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8071 --demo.product.process-latency-ms=100 --server.tomcat.threads.max=50"

# 实例 2（8072）—— 同服务名、不同端口
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl product-service spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8072 --demo.product.process-latency-ms=100 --server.tomcat.threads.max=50"

# 网关（8090，按 serviceId 自动路由，US-017 已配 discovery.locator）
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl gateway-service spring-boot:run
```

确认 Nacos 已收到两个实例：

```bash
curl -s "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=product-service"
# hosts 含 8071、8072 两个 healthy=true 实例
```

> 注意：新实例注册后，网关的 LoadBalancer 实例缓存有约 35s 的刷新延迟；停掉实例后同理需等缓存刷新，
> 否则可能短暂把请求转发到已下线实例。压测前先 warmup 几次确认 0 错误再正式打。

## 3. 压测对比（单实例 vs 双实例）

压测脚本 `jmeter/product_scale_test.jmx`：经网关压 `GET /product-service/products/7`，
200 并发线程 × 80 循环 = 16000 请求，ramp-up 5s。两次压测**客户端负载完全一致**，唯一变量是后端实例数。

```bash
cd jmeter
# 单实例：只起 8071（+ 网关），等 LB 刷新只剩 1 实例后压
jmeter -n -t product_scale_test.jmx -Jthreads=200 -Jloops=80 -l result_single.jtl -e -o report_single
# 双实例：再起 8072，等 LB 收到 2 实例后压
jmeter -n -t product_scale_test.jmx -Jthreads=200 -Jloops=80 -l result_multi.jtl -e -o report_multi
```

实测结果（每实例 `process-latency-ms=100` + `tomcat.threads.max=50`，16000 样本）：

| 指标 | 单实例 | 双实例 | 变化 |
|------|--------|--------|------|
| 吞吐量 throughput | 464.1 req/s | 853.8 req/s | **↑ 1.84×** |
| 平均响应 avg | 372.5 ms | 177.6 ms | ↓ 52% |
| TP90 | 426 ms | 215 ms | ↓ 50% |
| TP95 | 433 ms | 221 ms | ↓ 49% |
| TP99 | 587 ms | 235 ms | ↓ 60% |
| 错误率 | 0% | 0% | — |

**负载均衡分发证据**：双实例压测进行中，采样网关到两个实例的 ESTABLISHED 连接数为
`8071 ⇒ 150` 条、`8072 ⇒ 148` 条，近乎完美轮询，证明请求确实被均摊到两个实例。

**怎么读**：实例数翻倍，吞吐接近翻倍（1.84×，非精确 2× 是因为客户端固定 200 并发，
双实例下单请求排队减少、在途请求数略低于 100 个工作线程的满载点，外加网关转发开销）。
响应时间和 TP99 几乎砍半——同样的请求量分摊到两份处理能力上，每个请求排队更短。
这就是**无状态服务横向扩容**的典型收益曲线。

## 4. 横向扩容的前提

能这样「多开几个就快近一倍」，靠的是两个前提：

1. **服务无状态**：`product-service` 不在本地内存 / 本地磁盘存放会话或业务状态，任意实例都能处理任意请求，
   请求落到 8071 还是 8072 结果一致。有状态服务（比如把购物车存进进程内存）就不能随便加实例——
   请求被 LB 分到另一个实例就丢了状态。
2. **共享存储下沉到外部**：所有实例共享同一份 MySQL（`concurrency_demo`）与 Redis。状态都放在外部共享存储里，
   实例本身只是「无状态的计算单元」，于是可以随意增减。

> 推论：横向扩容扩的是**应用层（计算）**。一旦应用层不再是瓶颈，压力就会下沉到这份**共享的数据层**。

## 5. 瓶颈下移到数据库

本次演示里，瓶颈被刻意设计在**应用层**（每实例 50 线程 × 100ms 阻塞）。加实例 = 加应用层线程总数，
所以吞吐随实例数上涨。但这条曲线不会无限延伸：

- 应用层线程是「可水平扩展」的——多开进程就能加；
- 但所有实例**共享同一个 MySQL**，数据库的连接数、CPU、磁盘 I/O 是「相对固定」的资源。

当实例越加越多、应用层不再是瓶颈后，压力会**下沉到共享的 MySQL**：
每个 `product-service` 实例各自持有一个 HikariCP 连接池（默认 10 连接），N 个实例就是 N×10 条连接打向同一个
MySQL；继续加实例 → MySQL 连接数、行锁竞争、CPU 成为新的天花板，此时**再加应用实例吞吐也不再上涨**
（甚至因为连接争用而下降）。本演示中 MySQL 远未饱和（商品单点查询亚毫秒、瓶颈被 sleep 模拟在应用层），
所以没有触达这一步；但这是横向扩容必然会遇到的下一道墙。

**应对方向**（即本项目其余优化的意义所在）：

- 给热点读加 **Redis 缓存**（US-012），把读请求挡在 MySQL 之前，大幅降低打到库的 QPS；
- 读写分离 / 分库分表，把单库压力摊开；
- 连接池、慢查询、索引调优（US-010 批量、US-011 连接池），让每条连接更高效。

一句话总结：**横向扩容把应用层的墙推远，但推不动数据层的墙；最终要靠缓存与数据层架构来扛。**

## 6. 复现 checklist

```bash
# 0. 基础设施（mysql + nacos）
docker compose up -d mysql nacos

# 1. 网关 + 实例1
mvn -pl gateway-service spring-boot:run            # 8090
mvn -pl product-service spring-boot:run -Dspring-boot.run.arguments="--server.port=8071 --demo.product.process-latency-ms=100 --server.tomcat.threads.max=50"

# 2. 单实例压测（等 LB 只剩 1 实例）
cd jmeter && jmeter -n -t product_scale_test.jmx -Jthreads=200 -Jloops=80 -l result_single.jtl -e -o report_single

# 3. 加实例2 → 双实例压测（等 LB 收到 2 实例）
mvn -pl product-service spring-boot:run -Dspring-boot.run.arguments="--server.port=8072 --demo.product.process-latency-ms=100 --server.tomcat.threads.max=50"
cd jmeter && jmeter -n -t product_scale_test.jmx -Jthreads=200 -Jloops=80 -l result_multi.jtl -e -o report_multi

# 4. 对比 report_single 与 report_multi 的 statistics.json（throughput / pct*ResTime）
```

依赖：docker MySQL(3306) + Nacos(8848)。`process-latency-ms` 默认 0，不传则退回真实亚毫秒查询（打不出瓶颈）。
相关文档：服务拆分边界见 [MICROSERVICES.md](MICROSERVICES.md)，缓存挡库见 [CACHE-CONSISTENCY.md](CACHE-CONSISTENCY.md)，
连接池见 [HIKARICP.md](HIKARICP.md)。
