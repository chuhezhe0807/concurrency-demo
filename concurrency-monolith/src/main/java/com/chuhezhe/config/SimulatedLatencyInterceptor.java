package com.chuhezhe.config;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Statement;

/**
 * 模拟数据库网络往返延迟：在每条 SQL 真正执行前 sleep 固定毫秒。
 *
 * 为什么需要它：本机 docker MySQL 单次查询是亚毫秒级，无法复现原文“N+1 把
 * 61 次查询的延迟累加成数秒”的场景。注入一个 per-statement 延迟后：
 *   - N+1 列表(61 次查询) 的耗时 ≈ 61 × 延迟，被显著放大；
 *   - 批量查询(4 次) ≈ 4 × 延迟，对比鲜明。
 * 由于拦截的是 StatementHandler 的执行阶段，sleep 发生在连接被占用期间，
 * 因此也能真实制造连接池争用（US-011 的 HikariCP 演示）。
 *
 * demo.sim-db-latency-ms=0 即关闭，退回真实测量。
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class SimulatedLatencyInterceptor implements Interceptor {

    @Value("${demo.sim-db-latency-ms:0}")
    private long latencyMs;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (latencyMs > 0) {
            Thread.sleep(latencyMs);
        }
        return invocation.proceed();
    }
}
