package com.chuhezhe.service;

import com.chuhezhe.entity.Product;
import com.chuhezhe.mapper.ProductMapper;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 商品查询（US-012）。
 * <p>
 * 单个商品查询走 Redis 缓存：cache 名 product、key 为商品 id。
 * 用 condition="@optimizeProperties.cache" 让缓存只在开关打开时生效——关闭时既不读也不写，
 * 退回每次查库；unless 避免把不存在的商品(null)缓存进去造成穿透。
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Cacheable(cacheNames = "product", key = "#id",
            condition = "@optimizeProperties.cache", unless = "#result == null")
    public Product getById(Long id) {
        return productMapper.selectById(id);
    }
}
