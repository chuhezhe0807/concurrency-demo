# Seata 分布式事务：强一致（US-021）

## 1. 要解决的问题（承接 US-019 的缺口）

US-019 把下单拆成跨服务流程后，`order-service` 的 `placeOrder` 是这样的：

```
@Transactional                       // ← 只是 order-service 的本地事务
1) Feign 调 product-service 扣库存    // ← 扣减在 product 的本地事务里，独立提交
2) 本地 insert t_order               // ← 在 order 的本地事务里
```

本地 `@Transactional` 只能回滚第 2 步（t_order）。第 1 步的扣库存是**另一个进程、另一个本地事务**，
早就提交了，order 这边回滚根本管不到它。于是「**扣库存成功但建单失败**」会留下脏数据：
**库存被扣空，却没有对应订单**。这就是单体拆微服务后最典型的分布式事务问题。

> 注意：本项目里 order/product 两服务其实连的是**同一个物理库** `concurrency_demo`，但它们各自持有**独立的数据源 /
> 独立的本地事务**，互不知晓对方的提交/回滚。所以「同库」并不能省掉分布式事务——只要是两个独立事务，就需要一个
> 协调者把它们绑成一个整体。

## 2. Seata 怎么解：AT 模式 + 三个角色

Seata 用 **2PC（两阶段提交）**思路协调多个本地事务，三个角色：

| 角色 | 谁 | 职责 |
|------|----|----|
| TC（Transaction Coordinator，事务协调器） | 独立的 **Seata Server**（docker，端口 8091） | 维护全局事务状态，决定最终提交还是回滚，通知各分支 |
| TM（Transaction Manager，事务管理器） | **order-service**（`@GlobalTransactional` 所在处） | 向 TC 申请开启/提交/回滚全局事务 |
| RM（Resource Manager，资源管理器） | **order-service + product-service** 各自的数据源 | 管理本地分支事务，向 TC 注册分支、上报状态、执行二阶段提交/回滚 |

选 **AT 模式**（Automatic Transaction）的原因：扣库存就是一条标准 `UPDATE`，AT 能自动接管，无需像 TCC 那样
手写 try/confirm/cancel 三个方法。

### AT 模式两阶段（核心）

- **一阶段**：业务 SQL 正常执行，但 Seata 的**数据源代理**会在同一个本地事务里顺手做两件事：
  1. 解析 SQL，把**修改前的数据（前镜像）**和**修改后的数据（后镜像）**记录到业务库的 `undo_log` 表；
  2. 业务 SQL + undo_log **一起本地提交**（所以一阶段结束时本地事务已经提交，不长时间占锁）。
- **二阶段**（由 TC 通知）：
  - **全局提交**：什么都不用回滚，只需**异步删掉 undo_log**（很快）；
  - **全局回滚**：用 undo_log 里的**后镜像校验**当前数据有没有被别人改过（防脏写），没问题就用**前镜像还原**数据。

```
        ┌─────────────── order-service (TM + RM) ───────────────┐
POST →  │ @GlobalTransactional 向 TC 申请 XID                    │
        │   ├─ Feign 扣库存 ──XID透传──▶ product-service (RM)     │
        │   │                            扣库存SQL + 写undo_log + 本地提交 + 注册分支
        │   └─ insert t_order            建单SQL + 写undo_log + 本地提交 + 注册分支
        │ 方法正常返回 → TM 让 TC 全局提交 → 各分支异步删 undo_log │
        │ 方法抛异常   → TM 让 TC 全局回滚 → 各分支按 undo_log 还原 │
        └────────────────────────────────────────────────────────┘
```

**XID 透传**：TM 开启全局事务拿到一个全局事务 id（XID）。`spring-cloud-starter-alibaba-seata` 会自动给 Feign
加拦截器，把 XID 放进请求头透传到 product-service；product-service 的 RM 据此把自己的本地事务**挂到同一个 XID 下**
成为一个分支。这一步是“两个服务的事务被绑成一个”的关键，全自动、无需写代码。

## 3. 本项目的接入（改了什么）

### 基础设施
- `docker-compose.yml` 新增 `seata` 服务：`apache/seata-server:2.1.0`，端口 8091（TC）+ 7091（控制台 seata/seata），
  `STORE_MODE=file`（TC 会话存内存/文件，demo 够用）。
- `docker/mysql/init/01-schema-and-seed.sql` 增加 **`undo_log` 表**（AT 回滚日志，Seata 2.x 官方 DDL）。
  两服务同库，一张表即可。已初始化的库需用 `docker exec` 实时补建。

### 两个服务（依赖 + 配置）
- 各自 `pom.xml` 加 `com.alibaba.cloud:spring-cloud-starter-alibaba-seata`（由 SCA BOM 管到 **Seata 2.1.0**，
  groupId 是 Apache 版 `org.apache.seata`）。它自动：代理数据源（AT）、给 Feign 加 XID 透传、扫描 `@GlobalTransactional`。
- 各自 `application.yml` 加 `seata` 配置块（两边 `tx-service-group` 必须一致）：

```yaml
seata:
  enabled: true
  application-id: order-service          # product-service 处为 product-service
  tx-service-group: default_tx_group     # 两服务必须相同
  service:
    vgroup-mapping: { default_tx_group: default }   # 分组->集群名，须与 TC 集群名(默认 default)一致
    grouplist: { default: 127.0.0.1:8091 }          # file 模式直连 TC
  registry: { type: file }               # 不经 Nacos，直连 grouplist
  config:   { type: file }
  data-source-proxy-mode: AT
```

> 为什么 registry 用 file 直连而不走 Nacos：服务都跑在宿主机（mvn），seata 容器把 8091 映射到宿主机，
> 直连最简单也最稳；Nacos 此处仅用于服务发现（Feign 找 product-service），与 Seata 的 TC 寻址解耦。

### 代码
- `order-service` `OrderService.placeOrder`：加 `@GlobalTransactional(rollbackFor = Exception.class)`（保留 `@Transactional`）。
  新增失败注入：`CreateOrderRequest.mockFail=true` 时，在扣库存 + 建单都成功后故意抛异常，触发全局回滚。
- `product-service` `ProductService.deductStock`：加 `@Transactional`，使其成为一个规范的本地分支事务（AT 代理为其旁路 undo_log）。

## 4. 实测：成功提交 vs 失败回滚

启动 `seata`(8091) + product(8071) + order(8072) + gateway(8090)；两服务启动日志均出现
`register RM success. client version:2.1.0, server version:2.1.0 ... R:/127.0.0.1:8091` 与
`Auto proxy data source 'dataSource' by 'AT' mode.`，order 侧 `OrderService` 被 `GlobalTransactionalInterceptor` 织入。

基线：`product_7` stock=999985，`t_order`=30011 行，`undo_log`=0。

| 路径 | 请求 | HTTP | product_7 stock | t_order | undo_log | Seata Server 日志 |
|------|------|------|-----------------|---------|----------|-------------------|
| **成功提交** | `{userId:1,productId:7,quantity:3}` | 200 | 999985 → **999982**（-3） | +1 | 提交后回 0 | `Commit branch transaction successfully`×2（两分支）+ `Committing global transaction is successfully done` |
| **失败回滚** | `{...,quantity:3,mockFail:true}` | 500 | **999982 不变** | **不变** | 回滚后回 0 | `GlobalRollbackRequest` → `BranchRollbackResponse ... PhaseTwo_Rollbacked` → `Rollback global transaction successfully` |

**关键证据**：回滚路径里，扣库存是经 Feign 跨进程调 product-service 完成的，按 US-019 的本地事务根本回不来；
有了 Seata，product 分支被自动按 undo_log 还原，**stock 保持不变、订单也没落库**——跨服务强一致达成。
成功路径的 Seata 日志显示**两个分支**（product 扣减 + order 建单）都提交，证明两服务的本地事务确实被绑进了同一个全局事务。

复现命令：
```bash
docker compose up -d mysql nacos seata
# 已初始化的库补建 undo_log（DDL 见 init SQL / 本仓库）
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl product-service spring-boot:run   # 8071
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl order-service   spring-boot:run   # 8072
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl gateway-service spring-boot:run   # 8090
# 成功
curl -XPOST localhost:8090/order-service/orders -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3}'
# 回滚（库存应不变、无新订单）
curl -XPOST localhost:8090/order-service/orders -H 'Content-Type: application/json' -d '{"userId":1,"productId":7,"quantity":3,"mockFail":true}'
```

## 5. AT vs TCC，与局限

| | AT | TCC |
|--|----|----|
| 侵入性 | 低，业务 SQL 不变，加注解即可 | 高，每个操作写 try/confirm/cancel 三个方法 |
| 适用 | 标准关系型库的增删改，SQL 能被解析 | 任意资源（含非关系型）、需要更强控制时 |
| 隔离 | 全局锁防脏写，二阶段前数据已本地提交（读未提交，需 `@GlobalLock`/select for update 控读） | 由业务自己保证 |
| 本项目 | ✅ 扣库存就一条 UPDATE，选 AT | 留作了解 |

AT 局限：
- 只适合**关系型数据库**，且 SQL 要能被 Seata 解析（普通增删改没问题）。
- **全局锁**：一阶段提交后到二阶段结束前，对应行有全局锁，防止其它全局事务并发改同一行造成回滚覆盖；高并发热点行上会成为竞争点。
- 默认是**读未提交**级别——别的事务可能读到一阶段已提交、但全局还没最终提交的中间值；要严格读已提交需 `@GlobalLock` + `for update`。
- TC 用 `STORE_MODE=file` 时是单点、重启丢未决事务；生产应用 `db`/`raft` 存储 + TC 集群。

## 6. 与 US-022 的对比

Seata（强一致）追求“要么都成功要么都回滚、过程对外尽量不可见”，代价是**全局锁、同步等待、TC 依赖**，吞吐和可用性有损耗。
很多业务并不需要这么强——下单扣库存可以接受“最终一致”：先把订单做了，库存异步可靠地扣。US-022 会用 **RabbitMQ 可靠消息**
实现最终一致，再与本篇做选型对比（见后续 docs 与 [MICROSERVICES.md](MICROSERVICES.md) 的一致性缺口表）。
