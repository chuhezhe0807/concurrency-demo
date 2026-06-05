# 缓存一致性：竞态复现与三种解决方案（US-013）

本文配合 `demo.optimize.cache=true` 演示「商品缓存」在并发更新下的不一致问题，
并对比延迟双删、订阅 binlog、短过期兜底三种方案。所有演示均可在本机跑通，
观察接口/Redis/MySQL 三方的真实值。

相关代码：

- `ProductService.getById` —— `@Cacheable(product, key=#id)` 读缓存。
- `ProductService.updatePrice` —— `@CacheEvict(product, key=#id)` 改库后清缓存（基础正确写法）。
- `ProductService.updatePriceHoldingTx` —— 演示专用：删缓存→改库→hold 住事务不提交。
- `CacheRaceDemoService` —— 用真实线程精确编排竞态，返回观测结果。
- 接口：`GET /products/{id}`、`PUT /products/{id}?price=`、`POST /products/{id}/stale-race?newPrice=`。

---

## 1. 基础写法 `@CacheEvict` 解决的是什么

cache-aside 模式下，写操作必须让缓存失效，否则缓存里的旧值会一直被读命中直到 TTL：

```java
@CacheEvict(cacheNames = "product", key = "#id", condition = "@optimizeProperties.cache")
public int updatePrice(Long id, BigDecimal price) { ... }
```

`@CacheEvict` 解决的是「写完忘了删缓存」。但它**不能**解决并发下的「读回填旧值」竞态——
那需要下面的方案。

---

## 2. 竞态：并发更新 + 查询导致回填旧值

### 时序

```
时刻      写线程 A（@Transactional）            读线程 B（@Cacheable）
-----     -----------------------------         ----------------------------
t=0       cache.evict(5)   删缓存
          UPDATE price=777.77   改库【事务未提交】
          ……hold（模拟慢事务/GC/网络）……
t=400ms                                          cache miss（A 已删）
                                                 SELECT → 读到旧值 15.99
                                                 （A 未提交，REPEATABLE-READ 看不到）
                                                 cache.put(5, 15.99)   回填旧值
t=1500ms  方法返回 → COMMIT   库变为 777.77
-----     -----------------------------         ----------------------------
结果：MySQL = 777.77（新），Redis = 15.99（旧）—— 不一致，直到 TTL 到期才自愈
```

### 实测（POST /products/5/stale-race?newPrice=777.77）

```json
{
  "priceBefore": 15.99,
  "newPrice": 777.77,
  "threadB_readValue": 15.99,   // B 在 A 提交前读到旧值
  "dbAfter": 777.77,            // A 提交后库是新值
  "cacheAfter": 15.99,          // 缓存却是 B 回填的旧值
  "stale": true
}
```

### 根因

读的「查库」和「回填缓存」两步不是原子的，中间被写操作的「删缓存 + 改库（未提交）」插了进来。
`@CacheEvict` 删得再及时，也拦不住一个**已经读到旧值、正准备回填**的并发读。
MySQL 默认隔离级别 `REPEATABLE-READ`，B 在 A 提交前只能看到旧的已提交值，这是竞态的前提。

---

## 3. 三种解决方案对比

| 方案 | 做法 | 能否解决回填竞态 | 适用场景 | 局限 |
|------|------|------------------|----------|------|
| **延迟双删** | 写：删缓存 → 改库 → **延迟 N ms → 再删一次**。第二次删覆盖竞态期被回填的旧值 | 能（概率上，N 需大于一次读回填耗时） | 改动小、不想引入额外组件；能容忍写多一次延迟删 | 延迟时长难精确；第二次删失败仍脏；写路径变重 |
| **订阅 binlog**（Canal / binlog-connector） | 业务不在写路径删缓存；监听 MySQL binlog，**以提交后的变更**异步删缓存 | 能，且最彻底（以 DB 为准、写读解耦） | 一致性要求高、写频繁、多个写入口（含直连库的批处理） | 引入 binlog 订阅组件、运维成本高、有秒级延迟、消息需保证不丢 |
| **短过期兜底** | 不强求实时，靠短 TTL 让脏数据快速自愈 | 不能，只缩短脏窗口 | 能容忍秒级不一致（如商品详情、排行榜） | TTL 内仍是脏的；TTL 太短又会降低命中率 |

### 选型建议

- 多数业务：**延迟双删 + 短 TTL 兜底**。双删处理常规竞态，兜不住的极端情况由 TTL 收尾。
- 核心数据 / 多写入口 / 强一致诉求：上 **binlog 订阅**，把缓存失效与业务写解耦，以 DB 为唯一真相。
- 纯读多写少、容忍短暂旧值：**短 TTL** 足矣，最省事。

---

## 4. 复现命令

```bash
# 启动（cache 开关必须打开，竞态才存在）
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  mvn -DskipTests spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8080 --demo.optimize.cache=true --demo.sim-db-latency-ms=0"

# 触发竞态
curl -s -X POST "http://localhost:8080/products/5/stale-race?newPrice=777.77"; echo
# 事后再读，仍是旧值（脏数据持续）
curl -s "http://localhost:8080/products/5"; echo

# 观察三方
docker exec concurrency-demo-mysql mysql -udemo -pdemo123 concurrency_demo -N \
  -e "SELECT id,price FROM t_product WHERE id=5;"
docker exec concurrency-demo-redis redis-cli GET "product::5"

# 还原
docker exec concurrency-demo-mysql mysql -udemo -pdemo123 concurrency_demo \
  -e "UPDATE t_product SET price=15.99 WHERE id=5;"
docker exec concurrency-demo-redis redis-cli DEL "product::5"
```

> 时序参数在 `CacheRaceDemoService`：写线程 hold 1500ms、读线程 400ms 切入。
> 若某次没复现（`stale=false`），调大 hold 或读切入时间重试。

---

## 5. 三方案演示

以下章节随各方案实现逐个补充实测（延迟双删 / 订阅 binlog / 短过期兜底）。
