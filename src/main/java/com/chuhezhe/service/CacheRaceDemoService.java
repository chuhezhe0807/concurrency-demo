package com.chuhezhe.service;

import com.chuhezhe.entity.Product;
import com.chuhezhe.mapper.ProductMapper;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * US-013 缓存不一致竞态编排（仅供演示）。
 * <p>
 * 用真实线程精确控制时序，确定性复现 cache-aside 的经典竞态：
 * 写线程 A「先删缓存 → 改库（事务未提交）→ hold」，期间读线程 B miss 后从库读到旧值并回填缓存，
 * 待 A 提交后，库是新值而缓存是旧值。
 */
@Service
public class CacheRaceDemoService {

    private final ProductService productService;   // 代理对象：A 的事务、B 的 @Cacheable 都靠它
    private final ProductMapper productMapper;
    private final CacheManager cacheManager;

    public CacheRaceDemoService(ProductService productService,
                                ProductMapper productMapper,
                                CacheManager cacheManager) {
        this.productService = productService;
        this.productMapper = productMapper;
        this.cacheManager = cacheManager;
    }

    public Map<String, Object> run(Long id, BigDecimal newPrice) throws InterruptedException {
        Cache cache = cacheManager.getCache("product");

        // 准备：清掉缓存，记录改前的库值
        if (cache != null) {
            cache.evict(id);
        }
        BigDecimal priceBefore = productMapper.selectById(id).getPrice();

        // 线程 A（写）：先删缓存 → 改库 → hold 1500ms 不提交，方法返回后才 commit
        long holdMillis = 1500;
        Thread writer = new Thread(
                () -> productService.updatePriceHoldingTx(id, newPrice, holdMillis),
                "race-writer"
        );
        writer.start();

        // 等 A 删完缓存、执行完 UPDATE 并进入 hold（此刻事务未提交）
        Thread.sleep(400);

        // 线程 B（读）：缓存已被 A 删空 → miss → 查库。A 未提交，B 读到旧值并把旧值回填进缓存
        Product readByB = productService.getById(id);

        // 等 A 提交事务（库变为新值）
        // 调用 writer.join(); 后，主线程被阻塞，等待 writer 线程执行完
        writer.join();

        // 观测最终状态
        Cache.ValueWrapper vw = cache == null ? null : cache.get(id);
        BigDecimal cacheAfter = vw == null ? null : ((Product) vw.get()).getPrice();
        BigDecimal dbAfter = productMapper.selectById(id).getPrice();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("priceBefore", priceBefore);
        r.put("newPrice", newPrice);
        r.put("threadB_readValue", readByB == null ? null : readByB.getPrice());
        r.put("dbAfter", dbAfter);
        r.put("cacheAfter", cacheAfter);
        boolean stale = cacheAfter != null && dbAfter.compareTo(cacheAfter) != 0;
        r.put("stale", stale);
        r.put("说明", stale
                ? "缓存不一致复现成功：库=" + dbAfter + " 但缓存=" + cacheAfter + "（旧值），直到 TTL 到期才自愈"
                : "本次未复现（时序未命中，可重试或调大 holdMillis）");
        return r;
    }
}
