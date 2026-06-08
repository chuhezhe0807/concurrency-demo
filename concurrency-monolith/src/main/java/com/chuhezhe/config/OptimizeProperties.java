package com.chuhezhe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 优化开关：基线默认全部关闭，后续各阶段(US-010~US-015)逐个启用以对比效果。
 * 对应 application.yml 的 demo.optimize.*
 */
@Component
@ConfigurationProperties(prefix = "demo.optimize")
public class OptimizeProperties {

    /** N+1 批量查询优化（US-010） */
    private boolean batchQuery = false;
    /** HikariCP 连接池调优（US-011） */
    private boolean hikariTuning = false;
    /** Redis 缓存（US-012） */
    private boolean cache = false;
    /** 非核心逻辑异步化 @Async（US-014） */
    private boolean async = false;
    /** 消息队列替代 @Async（US-015） */
    private boolean mq = false;

    public boolean isBatchQuery() {
        return batchQuery;
    }

    public void setBatchQuery(boolean batchQuery) {
        this.batchQuery = batchQuery;
    }

    public boolean isHikariTuning() {
        return hikariTuning;
    }

    public void setHikariTuning(boolean hikariTuning) {
        this.hikariTuning = hikariTuning;
    }

    public boolean isCache() {
        return cache;
    }

    public void setCache(boolean cache) {
        this.cache = cache;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isMq() {
        return mq;
    }

    public void setMq(boolean mq) {
        this.mq = mq;
    }
}
