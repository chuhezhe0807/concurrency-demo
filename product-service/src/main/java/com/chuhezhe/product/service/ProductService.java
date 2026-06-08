package com.chuhezhe.product.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chuhezhe.product.entity.Product;
import com.chuhezhe.product.mapper.ProductMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品微服务的业务逻辑（US-018）。
 * <p>
 * 拆分版去掉了单体里的 Redis 缓存/一致性演示，聚焦微服务边界本身：直连 MySQL 提供
 * 查询、改价、扣库存。扣库存带「库存充足」判断（乐观更新），供 US-019 order-service 经 Feign 调用。
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public Product getById(Long id) {
        return productMapper.selectById(id);
    }

    public int updatePrice(Long id, BigDecimal price) {
        return productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .set(Product::getPrice, price)
                .set(Product::getUpdateTime, LocalDateTime.now())
                .eq(Product::getId, id));
    }

    /**
     * 扣库存：带「库存 >= quantity」条件的乐观更新，返回是否扣减成功。
     * 库存不足或商品不存在时返回 false（影响行数为 0），不抛异常，由调用方决定如何处理。
     */
    public boolean deductStock(Long id, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        int updated = productMapper.update(null, new LambdaUpdateWrapper<Product>()
                .setSql("stock = stock - " + quantity)
                .eq(Product::getId, id)
                .ge(Product::getStock, quantity));
        return updated > 0;
    }
}
