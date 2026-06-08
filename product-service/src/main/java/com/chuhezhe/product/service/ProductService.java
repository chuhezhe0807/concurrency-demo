package com.chuhezhe.product.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chuhezhe.product.entity.Product;
import com.chuhezhe.product.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * US-020 模拟每次查询的处理耗时（毫秒，默认 0 不生效）。代表慢下游/慢查询等阻塞型 I/O。
     * 配合限制 Tomcat 线程数，可让「单实例并发上限」成为瓶颈，从而在单机上直观演示横向扩容收益。
     */
    @Value("${demo.product.process-latency-ms:0}")
    private long processLatencyMs;

    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    public Product getById(Long id) {
        if (processLatencyMs > 0) {
            try {
                Thread.sleep(processLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
     * <p>
     * US-021：加 @Transactional 使本方法成为一个规范的本地事务。当请求携带 Seata 全局事务的 XID 时
     * （由 order-service 经 Feign 透传），该本地事务被纳入全局事务作为一个 RM 分支，AT 代理数据源会为这条
     * UPDATE 旁路生成 undo_log，供全局回滚时还原库存。
     */
    @Transactional
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
