package com.chuhezhe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 下单非核心任务的异步线程池（US-014）。
 * <p>
 * 队列满且线程达上限时的拒绝策略选 CallerRunsPolicy：由提交任务的线程（这里是处理下单的
 * Tomcat 线程）自己跑该任务，相当于「降级回同步」——既不丢任务，又通过让调用方变慢自然产生
 * 背压、减缓继续提交。代价是该次下单响应变慢。若改用默认的 AbortPolicy 则直接抛
 * RejectedExecutionException（丢任务、需上层兜底），适合「宁可失败也不拖慢」的场景。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("orderExecutor")
    public ThreadPoolTaskExecutor orderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
