package com.chuhezhe.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chuhezhe.entity.Product;
import com.chuhezhe.mapper.ProductMapper;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品查询与更新（US-012 缓存 / US-013 一致性）。
 * <p>
 * 查询走 Redis 缓存：cache 名 product、key 为商品 id。
 * 用 condition="@optimizeProperties.cache" 让缓存只在开关打开时生效——关闭时既不读也不写，
 * 退回每次查库；unless 避免把不存在的商品(null)缓存进去造成穿透。
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final CacheManager cacheManager;

    public ProductService(ProductMapper productMapper, CacheManager cacheManager) {
        this.productMapper = productMapper;
        this.cacheManager = cacheManager;
    }

    @Cacheable(cacheNames = "product", key = "#id",
            condition = "@optimizeProperties.cache", unless = "#result == null")
    public Product getById(Long id) {
        return productMapper.selectById(id);
    }

    /**
     * 改价（@CacheEvict 清缓存）——US-013 正确写法：改库后删除该商品的缓存，
     * 下次读 miss 回源拿到新值。condition 让 evict 只在开关开启(有缓存)时执行。
     */
    @CacheEvict(cacheNames = "product", key = "#id", condition = "@optimizeProperties.cache")
    public int updatePrice(Long id, BigDecimal price) {
        return doUpdatePrice(id, price);
    }

    /**
     * US-013 竞态演示专用：在一个事务里「先删缓存 → 改库 → 故意 hold 住 holdMillis 毫秒」，
     * 方法返回后事务才提交。hold 期间事务未提交，并发读会读到旧值并把旧值回填进缓存，
     * 待本方法返回提交后，库是新值、缓存是旧值 —— 复现 cache-aside 的经典不一致竞态。
     */
    @Transactional
    public void updatePriceHoldingTx(Long id, BigDecimal price, long holdMillis) {
        Cache cache = cacheManager.getCache("product");
        if (cache != null) {
            cache.evict(id);             // 写策略：先删缓存
        }
        doUpdatePrice(id, price);        // 改库（事务内，尚未提交）
        sleepQuietly(holdMillis);        // hold：方法返回后 Spring 才提交事务
    }

    /**
     * US-013 演示专用：只改库 + hold 住事务，不碰缓存。缓存的删除由调用方在事务外控制
     * （延迟双删需要在「提交后」再删一次，故不放在本事务方法里）。
     */
    @Transactional
    public void updatePriceTxHold(Long id, BigDecimal price, long holdMillis) {
        doUpdatePrice(id, price);
        sleepQuietly(holdMillis);
    }

    private int doUpdatePrice(Long id, BigDecimal price) {
        return productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .set(Product::getPrice, price)
                .set(Product::getUpdateTime, LocalDateTime.now())
                .eq(Product::getId, id));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
