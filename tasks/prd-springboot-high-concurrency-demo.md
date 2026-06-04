# PRD: Spring Boot 高并发处理演示项目

## 1. Introduction / 概述

本项目是一个**可复现的优化教程**，以一个最普通的 Spring Boot 订单后台管理系统为起点（几张表的 CRUD，日活三位数级别），通过 JMeter 压测暴露性能问题，再用 Arthas 等工具逐步诊断，演示从「慢」到「快」的完整优化链路。

每一步优化（N+1 修复、连接池调优、Redis 缓存、异步化/消息队列）都**保留在同一仓库中，通过配置开关切换**，使学习者可以在一台开发机上分别压测优化前后的版本，亲眼看到每个动作带来的数据变化，并理解每一步的原因与代价。

项目分两个阶段：

- **阶段一（单体优化）**：在单体 Spring Boot 应用内走完从「慢」到「快」的优化链路（本 PRD 主体，US-001 ~ US-010）。
- **阶段二（微服务演进，进阶）**：当单体优化到顶后，把热点服务（如订单、商品）拆分为独立微服务，对热点服务**横向扩容**提升并发处理能力，并处理由此引入的**分布式事务**问题（US-011 ~ US-013）。

目标读者是准备面试或学习高并发实战的后端开发者：重点不是「扛过多少 QPS」，而是「碰到性能问题知道如何一步步排查和解决」。

所有第三方依赖（MySQL、Redis、RabbitMQ 等）统一通过 **docker-compose** 启动，使用**真实的 MySQL 数据库**并预置测试表与测试数据，保证可复现。

## 2. Goals / 目标

- 提供一个开箱即用、单机可跑通的 Spring Boot 订单演示项目（默认配置即「未优化」状态）。
- 每个优化阶段都可以通过配置开关（feature flag）独立开启/关闭，便于压测对比。
- 每个阶段提供 JMeter 压测脚本与可记录的对比指标（平均响应时间、吞吐量、错误率、TP99）。
- 文档化每一步：要解决什么问题、用什么工具诊断、怎么改、改完效果、有什么代价/局限。
- 覆盖原文全部优化手段：批量查询（N+1）、HikariCP 连接池调优、Redis 缓存（含一致性问题）、@Async 异步线程池、消息队列替代方案。
- 不引入分布式锁、分库分表、集群、K8s 等重型方案——全程基础手段、单机可验证。

## 3. User Stories / 用户故事

### US-000: docker-compose 环境与真实 MySQL 测试数据
**Description:** 作为学习者，我需要用 docker-compose 一键启动所有第三方依赖，并拥有一个预置了测试表和测试数据的真实 MySQL 库，保证整套演示可复现。

**Acceptance Criteria:**
- [ ] 提供 `docker-compose.yml`，一键启动 MySQL、Redis、RabbitMQ（含管理界面）。
- [ ] 使用真实 MySQL（非内存库/H2），版本与端口、账号在文档中写明。
- [ ] 提供初始化 SQL（建表 + 造数），包含 order / user / product / logistics 等表。
- [ ] 测试数据量级足以稳定复现 N+1 与连接争用（订单、用户、商品达到一定规模，例如订单数十万级）。
- [ ] 文档说明 `docker-compose up -d` 启动、数据自动初始化、应用如何连接。
- [ ] 应用启动后能连上 MySQL 并查到预置数据。

### US-001: 搭建基线订单项目（未优化）
**Description:** 作为学习者，我需要一个能跑起来、但故意未做任何并发优化的 Spring Boot 订单系统，作为压测的起点。

**Acceptance Criteria:**
- [ ] Spring Boot 项目可启动，提供订单列表接口 `GET /orders` 和下单接口 `POST /orders`。
- [ ] 包含 order / user / product / logistics 等表及初始化数据（数据量足以体现 N+1，例如订单 20 条/页，关联用户、商品、物流）。
- [ ] 默认配置：Tomcat 200 线程，HikariCP 10 连接，无任何调优。
- [ ] 订单列表接口内部刻意使用 for 循环逐条查询用户/商品/物流（制造 N+1）。
- [ ] 项目编译通过、能启动、接口能返回数据。

### US-002: 提供基线 JMeter 压测脚本与基线指标
**Description:** 作为学习者，我需要一份压测脚本和基线数据，量化「优化前有多慢」。

**Acceptance Criteria:**
- [ ] 提供 `order_list_test.jmx`（200 并发请求订单列表接口）。
- [ ] 文档记录运行命令：`jmeter -n -t order_list_test.jmx -l result.jtl -e -o report/`。
- [ ] 文档记录基线指标占位表：平均响应时间、吞吐量(req/s)、错误率、TP99。
- [ ] 说明如何阅读 JMeter 报告中的关键数字。

### US-003: Arthas 诊断接口耗时
**Description:** 作为学习者，我需要学会用 Arthas 定位接口内部哪一步最慢，而不是凭直觉调大线程数。

**Acceptance Criteria:**
- [ ] 文档说明 `java -jar arthas-boot.jar` 与 `trace com.example.OrderController listOrders` 的用法。
- [ ] 文档展示并解读 trace 输出，定位到 `fillOrderDetails` 占用绝大部分耗时。
- [ ] 明确说明「线程数不是瓶颈时调大线程数无用」这一反直觉结论。

### US-004: 修复 N+1（批量查询）
**Description:** 作为学习者，我希望把 for 循环逐条查询改为批量查询，看到响应时间和吞吐量的提升。

**Acceptance Criteria:**
- [ ] 提供配置开关（如 `demo.optimize.batch-query=true/false`）切换 N+1 实现与批量实现。
- [ ] 批量实现：收集所有 userId/productId 后用 `WHERE id IN (...)` 一次查出，内存里 Map 匹配；商品、物流同理。
- [ ] 61 次查询降为约 4 次。
- [ ] 文档记录优化后压测指标对比（预期：响应时间从 ~4200ms 降到 ~320ms，吞吐量从 38 升到 ~280 req/s）。
- [ ] 文档补充：IN 子句过大时分批、MyBatis `<foreach>` 写法、索引影响等延伸知识点。

### US-005: HikariCP 连接池调优
**Description:** 作为学习者，我希望调整连接池参数，消除连接等待超时导致的错误率和 TP99 毛刺。

**Acceptance Criteria:**
- [ ] 通过配置开关切换默认连接池配置与调优后配置。
- [ ] 调优配置：`maximum-pool-size: 30`、`minimum-idle: 10`、`connection-timeout: 3000`。
- [ ] 文档展示并解读 HikariCP 的 `Connection is not available, request timed out` 日志。
- [ ] 文档记录优化后指标（预期：错误率降到 0，TP99 从 ~1800ms 降到 ~450ms）。
- [ ] 文档补充：连接数 ≈ CPU 核数 * 2 + 磁盘数 的结论（连接非越多越好）、`leak-detection-threshold` 排查连接泄漏。

### US-006: Redis 缓存热点查询
**Description:** 作为学习者，我希望对一天才更新一次的商品数据加缓存，进一步提升吞吐量。

**Acceptance Criteria:**
- [ ] 通过配置开关启用/禁用缓存。
- [ ] 用 `@Cacheable(value="product", key="#productId")` 缓存商品查询。
- [ ] 文档记录优化后指标（预期：吞吐量提升到 ~380 req/s）。
- [ ] 需要可用的 Redis（提供 docker 启动命令或说明）。

### US-007: 演示缓存一致性问题及处理
**Description:** 作为学习者，我需要看到缓存带来的数据不一致问题，并理解几种处理方案的取舍。

**Acceptance Criteria:**
- [ ] 用 `@CacheEvict(value="product", key="#product.id")` 演示更新时删除缓存。
- [ ] 文档描述并发更新+查询导致回填旧值的竞态场景。
- [ ] 文档对比延迟双删、订阅 binlog、短过期时间兜底三种方案的适用场景与局限。

### US-008: 非核心逻辑异步化（@Async）
**Description:** 作为学习者，我希望把下单接口里的发短信/邮件/加积分等非核心逻辑异步化，缩短接口响应时间。

**Acceptance Criteria:**
- [ ] 下单接口同步只做「扣库存 + 创建订单」，其余丢入 `@Async("orderExecutor")` 线程池。
- [ ] 通过配置开关切换同步/异步实现。
- [ ] 文档记录下单接口响应从 ~1s+ 降到 ~200ms。
- [ ] 文档补充：自定义线程池配置、拒绝策略（CallerRunsPolicy vs 直接拒绝）。

### US-009: 消息队列替代 @Async（进阶）
**Description:** 作为学习者，当非核心任务量大或不能丢失时，我希望用消息队列替代 @Async 实现解耦与持久化。

**Acceptance Criteria:**
- [ ] 通过配置开关在 @Async 与 MQ 实现间切换（RabbitMQ 或 Kafka，二选一并说明选择）。
- [ ] 生产者把下单后任务投递到队列，消费者异步处理。
- [ ] 文档补充：消息丢失保障（publisher confirm + 持久化 + 手动 ack）、重复消费的幂等性设计。
- [ ] 需要可用的 MQ（提供 docker 启动命令或说明）。

### US-010: 汇总对比与教程文档
**Description:** 作为学习者，我希望有一份串起全流程的文档，从头到尾对比每一步的指标与原理。

**Acceptance Criteria:**
- [ ] 文档汇总最初 vs 最终指标（预期：从 38 req/s、4200ms、12% 错误率 → 500+ req/s、~80ms、0 错误率）。
- [ ] 每个阶段列出：开关如何打开、压测命令、对比数字、面试可展开的知识点。
- [ ] 说明如何按阶段逐个打开开关复现整条优化曲线。

### US-011: 拆分热点服务为微服务（阶段二）
**Description:** 作为学习者，当单体优化到顶后，我希望把热点服务（订单、商品）拆为独立微服务，理解单体到微服务的演进动机与代价。

**Acceptance Criteria:**
- [ ] 把订单、商品（按热点）拆为独立 Spring Boot 服务，各自独立部署、独立连接 MySQL。
- [ ] 引入服务注册发现与网关（如 Nacos/Eureka + Spring Cloud Gateway，文档说明选型）。
- [ ] 服务间调用通过 OpenFeign 或 HTTP 客户端，下单流程跨服务串联。
- [ ] docker-compose 增加注册中心/网关，仍可一键启动。
- [ ] 文档说明拆分边界、引入的新问题（网络调用、数据一致性）。

### US-012: 热点服务横向扩容
**Description:** 作为学习者，我希望对热点服务部署多个实例并做负载均衡，验证横向扩容带来的并发处理能力提升。

**Acceptance Criteria:**
- [ ] 通过 docker-compose 启动热点服务的多个实例。
- [ ] 经网关/客户端负载均衡（如 Spring Cloud LoadBalancer）分发请求。
- [ ] 提供 JMeter 压测对比：单实例 vs 多实例的吞吐量与响应时间。
- [ ] 文档说明横向扩容的前提（服务无状态、共享 Redis/MySQL）与瓶颈下移现象（压力转移到数据库）。

### US-013: 分布式事务处理
**Description:** 作为学习者，拆分后下单跨服务（扣库存在商品服务、创建订单在订单服务），我需要保证跨服务数据一致性。

**Acceptance Criteria:**
- [ ] 演示跨服务下单场景下不做处理时的数据不一致问题。
- [ ] 演示方案一：**Seata（强一致，AT 或 TCC）**，含成功提交与失败回滚两条路径。
- [ ] 演示方案二：**基于 RabbitMQ 的可靠消息最终一致性**，含正常流程与补偿/重试路径。
- [ ] 文档对比两种方案：强一致 vs 最终一致的适用场景、性能代价、复杂度。
- [ ] 所需中间件（Seata Server、RabbitMQ）纳入 docker-compose。

## 4. Functional Requirements / 功能需求

- FR-1: 系统必须提供订单列表接口 `GET /orders`（分页，默认每页 20 条，关联用户、商品、物流信息）。
- FR-2: 系统必须提供下单接口 `POST /orders`（扣库存、创建订单为核心同步逻辑；发短信、发邮件、加积分为非核心逻辑）。
- FR-3: 系统必须通过统一的配置开关（如 `application.yml` 下 `demo.optimize.*`）控制每个优化阶段的开启/关闭，默认全部关闭（即基线未优化状态）。
- FR-4: N+1 优化开关开启时，订单详情填充必须使用批量 `IN` 查询；关闭时使用逐条查询。
- FR-5: 连接池优化开关必须能在默认配置（10 连接 / 30s 超时）与调优配置（30 连接 / 3s 超时）间切换。
- FR-6: 缓存开关开启时，商品查询走 `@Cacheable`，商品更新走 `@CacheEvict`。
- FR-7: 异步开关开启时，下单接口的非核心逻辑通过自定义线程池 `orderExecutor` 异步执行。
- FR-8: MQ 开关开启时，下单后非核心任务通过消息队列投递与消费。
- FR-9: 项目必须提供每个阶段对应的 JMeter `.jmx` 脚本及运行说明。
- FR-10: 项目必须提供一份阶段化教程文档，记录命令、对比指标与延伸知识点。
- FR-11: 所有第三方依赖（MySQL、Redis、RabbitMQ，阶段二的注册中心/网关/Seata 等）必须统一通过 `docker-compose` 启动。
- FR-12: 必须使用真实 MySQL，并通过初始化 SQL 预置测试表与足量测试数据（不使用 H2/内存库）。
- FR-13: 阶段二必须能把订单/商品热点服务拆为独立微服务，并支持横向扩容（多实例 + 负载均衡）。
- FR-14: 阶段二跨服务下单必须提供分布式事务方案，覆盖提交成功与失败回滚/补偿两条路径。

## 5. Non-Goals / 范围外

- 不实现分库分表、读写分离。
- 不搭建 Redis / MQ / MySQL 集群，不涉及生产级高可用部署。
- 不涉及 K8s、服务网格、自动弹性扩缩容（阶段二的横向扩容靠 docker-compose 手动多实例 + 负载均衡演示，不做自动扩容）。
- 不追求真实百万 QPS，所有验证在单台开发机（docker-compose）完成。
- 不实现完整的前端页面；接口验证以 JMeter / curl / Postman 为准（无 UI 故事）。
- 不实现真实的短信/邮件发送，用 sleep 或日志模拟其耗时即可。

## 6. Technical Considerations / 技术考量

- 技术栈：Java 21、Spring Boot、**MyBatis-Plus**（`listByIds` / `<foreach>` 省事）、HikariCP、Spring Cache + Redis、**RabbitMQ**。
- 数据库：**真实 MySQL**（贴合原文 `WHERE id IN`、HikariCP 场景），不使用 H2/内存库；初始化脚本需造足量数据以体现 N+1 与连接争用。
- 代码组织：阶段一同仓库通过 feature flag 切换，优化前后实现并存（如策略类 + 配置注入），避免靠多分支 checkout。
- 第三方依赖统一用 **docker-compose** 启动（MySQL / Redis / RabbitMQ；阶段二增加注册中心、网关、Seata 等），降低复现门槛。
- 诊断工具：Arthas（trace 接口耗时）、HikariCP 日志、JMeter 报告。
- 现有项目为纯 Maven（无 Spring Boot 依赖），需在 `pom.xml` 引入 Spring Boot parent 及相关 starter。
- 阶段二（微服务）选型建议：Spring Cloud（Nacos 或 Eureka 注册发现 + Spring Cloud Gateway + OpenFeign + LoadBalancer），分布式事务用 Seata 或基于 RabbitMQ 的可靠消息最终一致性。阶段二应在阶段一稳定后再启动，避免一上来就上微服务。

## 7. Success Metrics / 成功指标

- 学习者能在单机按文档逐步打开开关，复现出与文档接近的指标曲线。
- 全开关关闭（基线）：约 38 req/s、~4200ms 平均响应、~12% 错误率。
- 全开关开启（最终）：500+ req/s、~80ms 平均响应、0 错误率。
- 每个优化阶段都有清晰的「改前/改后」对比数字与可展开的面试知识点。

## 8. Decisions & Open Questions / 已定决策与待确认问题

已确认：
- 消息队列：**RabbitMQ**。
- ORM：**MyBatis-Plus**。
- 数据库：**真实 MySQL**，预置测试表与测试数据。
- 第三方服务：统一 **docker-compose** 启动。
- 演进路线：单体优化（阶段一）→ 微服务拆分 + 热点服务横向扩容 + 分布式事务（阶段二）。
- 阶段二注册中心：**Nacos**（兼配置中心）。
- 分布式事务：**Seata（强一致 AT/TCC）与 RabbitMQ 可靠消息最终一致性两者都演示**，作为对比，文档说明各自适用场景与代价。
- Arthas trace：仅作为**文档手动演示步骤**，不纳入自动化验证。
- 初始化数据量级：**取适中规模即可**（开发者按需设定），N+1 问题从代码逻辑即可看出，无需靠数据量完全复现；数据量主要服务于连接争用与压测体感。

待确认：
- 无（关键决策已全部确定，可进入实现阶段）。
