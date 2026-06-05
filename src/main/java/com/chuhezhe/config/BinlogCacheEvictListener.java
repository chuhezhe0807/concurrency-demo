package com.chuhezhe.config;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * US-013 方案二：订阅 binlog 删缓存（简化版 Canal）。
 * <p>
 * 以伪从库身份连接 MySQL，监听 t_product 的行变更事件。binlog 只在事务**提交后**才产生，
 * 因此以 binlog 为准删缓存能避开「读回填旧值」竞态——业务写路径完全不碰缓存。
 * 通过 demo.binlog-cache-evict=true 才启用（默认关闭，不影响其它演示）。
 */
@Component
@ConditionalOnProperty(name = "demo.binlog-cache-evict", havingValue = "true")
public class BinlogCacheEvictListener {

    private static final Logger log = LoggerFactory.getLogger(BinlogCacheEvictListener.class);
    private static final String TARGET_DB = "concurrency_demo";
    private static final String TARGET_TABLE = "t_product";

    private final CacheManager cacheManager;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final long serverId;

    /** binlog 事件里 tableId → "db.table"，由 TABLE_MAP 事件维护 */
    private final Map<Long, String> tableIdToName = new ConcurrentHashMap<>();

    private BinaryLogClient client;
    private Thread thread;

    public BinlogCacheEvictListener(CacheManager cacheManager,
                                    @Value("${demo.binlog.host:localhost}") String host,
                                    @Value("${demo.binlog.port:3306}") int port,
                                    @Value("${spring.datasource.username}") String username,
                                    @Value("${spring.datasource.password}") String password,
                                    @Value("${demo.binlog.server-id:65535}") long serverId) {
        this.cacheManager = cacheManager;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.serverId = serverId;
    }

    @PostConstruct
    public void start() {
        client = new BinaryLogClient(host, port, username, password);
        client.setServerId(serverId);
        client.registerEventListener(this::onEvent);
        thread = new Thread(() -> {
            try {
                log.info("[BINLOG] 连接 {}:{} 开始监听 {}.{} 变更以删缓存", host, port, TARGET_DB, TARGET_TABLE);
                client.connect();
            } catch (Exception e) {
                log.error("[BINLOG] 监听异常", e);
            }
        }, "binlog-cache-evict");
        thread.setDaemon(true);
        thread.start();
    }

    private void onEvent(com.github.shyiko.mysql.binlog.event.Event event) {
        EventData data = event.getData();
        if (data instanceof TableMapEventData tm) {
            tableIdToName.put(tm.getTableId(), tm.getDatabase() + "." + tm.getTable());
            return;
        }
        if (data instanceof UpdateRowsEventData u && isTarget(u.getTableId())) {
            u.getRows().forEach(rows -> evictByRow(rows.getValue())); // after-image
        } else if (data instanceof WriteRowsEventData w && isTarget(w.getTableId())) {
            w.getRows().forEach(this::evictByRow);
        } else if (data instanceof DeleteRowsEventData d && isTarget(d.getTableId())) {
            d.getRows().forEach(this::evictByRow);
        }
    }

    private boolean isTarget(long tableId) {
        return (TARGET_DB + "." + TARGET_TABLE).equals(tableIdToName.get(tableId));
    }

    /** t_product 列序：id(0), name(1), price(2), stock(3), update_time(4), deleted(5)，取 id 删缓存 */
    private void evictByRow(Serializable[] row) {
        if (row == null || row.length == 0 || row[0] == null) {
            return;
        }
        long id = ((Number) row[0]).longValue();
        Cache cache = cacheManager.getCache("product");
        if (cache != null) {
            cache.evict(id);
            log.info("[BINLOG] 监听到 t_product id={} 变更，已删缓存 product::{}", id, id);
        }
    }

    @PreDestroy
    public void stop() throws Exception {
        if (client != null) {
            client.disconnect();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
