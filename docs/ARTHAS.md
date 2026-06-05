# Arthas 诊断：定位接口内部最慢的一步（手动演示）

> 本篇是**手动演示步骤**，不纳入自动化验证（US-009）。
> 目标：在不改一行代码、不重启应用的前提下，用 Arthas 在线 `trace` 出
> `GET /orders` 接口里到底哪一步最慢，并据此得出“盲目调大线程数无用”的反直觉结论。

前置条件：应用以**基线配置**运行（`demo.optimize.*` 全 false，`demo.sim-db-latency-ms=5`），
即 US-008 里那个 N+1 慢接口仍然生效。

---

## 1. 启动 Arthas 并 attach 到应用

Arthas 通过 attach 到目标 JVM 进程工作，全程在线，不需要重启被诊断的应用。

### 方式 A：官方 arthas-boot.jar（通用）

```bash
# 下载（只需一次）
curl -O https://arthas.aliyun.com/arthas-boot.jar

# 启动后会列出本机所有 Java 进程，输入 concurrency-demo 对应的序号回车即可 attach
java -jar arthas-boot.jar
```

attach 成功后会看到进程被选中、Arthas 版本号，以及一个 `[arthas@<pid>]$` 交互提示符。

### 方式 B：本仓库已下载的 as.sh（等价，更省事）

仓库根目录的 `as.sh` 是官方 Arthas 启动脚本（v4.2.2），与 arthas-boot.jar 作用相同：

```bash
# 不带参数：列出 Java 进程供选择
./as.sh

# 或直接 attach 到已知 pid（用 jps 查 ConcurrencyDemoApplication 的 pid）
jps -l | grep ConcurrencyDemoApplication
./as.sh <pid>
```

> attach 后还会顺带启动一个 Web Console（默认 http://127.0.0.1:8563），命令行和网页效果一致。

退出诊断会话用 `quit`（仅断开当前会话，应用不受影响）；彻底关闭 Arthas 用 `stop`。

---

## 2. 用 trace 逐层下钻，定位最慢的一步

`trace` 会统计某个方法**内部每个子调用的耗时占比**，是定位“慢在哪一步”的主力命令。

### 第一层：trace 入口方法 listOrders

一边执行下面的命令，一边在另一个终端打一发请求触发它：

```bash
# Arthas 会话里执行
trace com.chuhezhe.service.OrderService listOrders

# 另开终端触发一次请求
curl 'http://localhost:8080/orders?pageNo=1&pageSize=20'
```

捕获到一次调用后，输出形如：

```
`---ts=2026-06-05 11:20:33;thread_name=http-nio-8080-exec-3;id=2b;is_daemon=true;priority=5;TCCL=...
    `---[762.345ms] com.chuhezhe.service.OrderService:listOrders()
        +---[0.63%] [4.78ms] com.baomidou.mybatisplus.core.mapper.BaseMapper:selectPage() #102
        `---[99.1%] [755.91ms] com.chuhezhe.service.OrderService:fillOrderDetails() #103
```

**怎么读**：
- 行首 `[762.345ms]` 是 `listOrders` 这一整次调用的总耗时。
- 缩进的每一行是它的子调用，`[百分比]` 是占父方法耗时的比例，`[xx ms]` 是该子调用耗时，`#102`/`#103` 是源码行号。
- 一眼可见：分页查询 `selectPage` 只占 **0.6%**，而 `fillOrderDetails` 独占 **99.1%**。
  瓶颈不在“查列表”，而在“填充明细”。继续往里钻。

### 第二层：trace fillOrderDetails，看清 N+1

```bash
trace com.chuhezhe.service.OrderService fillOrderDetails
```

再触发一次请求，输出形如：

```
`---[755.91ms] com.chuhezhe.service.OrderService:fillOrderDetails()
    +---[33.5%] [253.2ms] com.chuhezhe.mapper.UserMapper:selectById() #113 [20 times]
    +---[33.2%] [251.0ms] com.chuhezhe.mapper.ProductMapper:selectById() #114 [20 times]
    +---[32.9%] [248.7ms] com.chuhezhe.mapper.LogisticsMapper:selectOne() #115 [20 times]
```

**怎么读**：
- 末尾的 `[20 times]` 是关键信号——同一个 `selectById` 在一次方法调用里被执行了 **20 次**。
- 三个 Mapper 各 20 次 = 60 次明细查询，加上第一层那 1 次 `selectPage`，正好是
  **1 + 20×3 = 61 次数据库往返**，这就是教科书式的 **N+1**。
- 每次查询都带 5ms 的模拟网络往返（`SimulatedLatencyInterceptor`），60 次叠加 ≈ 300ms，
  再算上连接获取/序列化等开销，单请求就被拖到数百毫秒级；并发上来后（US-008 实测 200 并发
  平均 7979ms）情况更糟。

> 想看每条 SQL 实参，可用 `watch com.chuhezhe.mapper.UserMapper selectById '{params,returnObj}' -x 2`，
> 或对 Mapper 方法本身再 `trace`。

---

## 3. 反直觉结论：线程数不是瓶颈时，调大线程数没用

新手遇到“接口慢、吞吐低”，第一反应往往是**调大 Tomcat 线程数**。用 Arthas 看一眼线程状态，就知道为什么这条路走不通。

```bash
# 看最忙的几个线程在干什么
thread -n 8

# 或统计各状态线程数量
thread
```

压测期间你会看到大量 `http-nio-8080-exec-*` 线程，栈顶停在类似：

```
"http-nio-8080-exec-37" Id=131 TIMED_WAITING
    at java.base@21/java.lang.Thread.sleep(Native Method)
    at com.chuhezhe.config.SimulatedLatencyInterceptor.intercept(...)   # 模拟 DB 往返
    at ...MyBatis StatementHandler.query(...)
    at com.chuhezhe.mapper.UserMapper.selectById(...)
    at com.chuhezhe.service.OrderService.fillOrderDetails(...)
```

线程并不是“忙于计算”，而是**全都阻塞在等待数据库返回**（`TIMED_WAITING`/`BLOCKED`，CPU 占用极低）。

**为什么调大线程数无用——甚至更糟：**
- 瓶颈是**每个请求要串行等 61 次 DB 往返**，单请求的墙钟时间由这条串行链决定，
  跟有多少个工作线程无关。线程再多，每个请求该等的还是得等。
- 真正的硬约束在下游：**HikariCP 默认只有 10 条连接**。线程从 200 加到 400，只是让
  更多线程同时去抢那 10 条连接，**排队更长**，TP99 不降反升，还可能逼近连接超时。
- 把 200 线程调成 400，吞吐量纹丝不动，因为系统瓶颈在“DB 往返次数 × 单次延迟 + 连接数”，
  不在“能并发处理多少请求”。

**正确的优化方向**（后续 user story 逐个验证）：
1. **US-010** 把 N+1 改成批量 `IN` 查询，61 次 → ~4 次——直接砍掉串行链长度，这是收益最大的一步。
2. **US-011** 给 HikariCP 扩容并调小超时，缓解连接争用、让失败快速暴露。
3. **US-012** 给热点商品查询加 Redis 缓存，进一步减少打到 DB 的往返。

一句话总结：**先用 Arthas 找到真正的瓶颈，再对症下药；在 I/O 等待型瓶颈上盲目加线程，
只会把排队从一个地方挪到另一个地方。**

---

## 附：本篇用到的 Arthas 命令速查

| 命令 | 作用 |
|------|------|
| `./as.sh` 或 `java -jar arthas-boot.jar` | 启动并 attach 到目标 JVM |
| `trace <类> <方法>` | 统计方法内部各子调用耗时占比，逐层下钻定位最慢一步 |
| `trace <类> <方法> -n 5` | 最多捕获 5 次调用后自动结束 |
| `watch <类> <方法> '{params,returnObj}' -x 2` | 观察方法入参/返回值 |
| `thread` / `thread -n 8` | 查看线程状态分布 / 最忙的 N 个线程栈 |
| `thread -b` | 找出导致阻塞的线程 |
| `quit` | 退出当前会话（应用不受影响） |
| `stop` | 关闭 Arthas |
