package com.chuhezhe.product.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chuhezhe.common.dto.StockDeductMsg;
import com.chuhezhe.product.entity.Product;
import com.chuhezhe.product.entity.StockDeductLog;
import com.chuhezhe.product.mapper.ProductMapper;
import com.chuhezhe.product.mapper.StockDeductLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductMapper productMapper;
    private final StockDeductLogMapper deductLogMapper;

    /**
     * US-020 模拟每次查询的处理耗时（毫秒，默认 0 不生效）。代表慢下游/慢查询等阻塞型 I/O。
     * 配合限制 Tomcat 线程数，可让「单实例并发上限」成为瓶颈，从而在单机上直观演示横向扩容收益。
     */
    @Value("${demo.product.process-latency-ms:0}")
    private long processLatencyMs;

    public ProductService(ProductMapper productMapper, StockDeductLogMapper deductLogMapper) {
        this.productMapper = productMapper;
        this.deductLogMapper = deductLogMapper;
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

    /** 扣库存结果：success=是否扣减成功，reason=失败/重复原因。 */
    public record DeductOutcome(boolean success, String reason) {
    }

    /**
     * US-022 可靠消息消费端的幂等扣库存。与去重表写在同一事务里：
     * <ol>
     *   <li>先 insert 幂等表（order_no 主键）。主键冲突=这条消息处理过了 → 直接返回成功，不重复扣减
     *       （应对消息「至少一次」重投）。</li>
     *   <li>未冲突则执行扣库存：库存充足返回成功，不足返回失败（业务结果，非异常，照常提交去重记录避免无谓重试）。</li>
     * </ol>
     * 若过程抛异常，事务回滚（连同去重记录），消息 nack 后可被重新处理。
     */
    @Transactional
    public DeductOutcome deductForOrder(StockDeductMsg msg) {
        try {
            deductLogMapper.insert(new StockDeductLog(msg.getOrderNo(), msg.getProductId(), msg.getQuantity()));
        } catch (DuplicateKeyException dup) {
            log.info("[STOCK] 消息已处理过，幂等跳过 orderNo={}", msg.getOrderNo());
            return new DeductOutcome(true, "duplicate-already-processed");
        }
        boolean ok = deductStock(msg.getProductId(), msg.getQuantity());
        if (ok) {
            // 同一事务内把占位行标记为「已扣」。供 DLQ 补偿区分「真扣了」与「库存不足没扣」。
            StockDeductLog deducted = new StockDeductLog();
            deducted.setOrderNo(msg.getOrderNo());
            deducted.setDeducted(true);
            deductLogMapper.updateById(deducted);
            log.info("[STOCK] 扣库存成功 orderNo={} productId={} qty={}",
                    msg.getOrderNo(), msg.getProductId(), msg.getQuantity());
            return new DeductOutcome(true, "ok");
        }
        // 库存不足：占位行保持 deducted=false（其实没扣），照常提交避免无谓重试。
        log.warn("[STOCK] 库存不足，扣减失败 orderNo={} productId={} qty={}",
                msg.getOrderNo(), msg.getProductId(), msg.getQuantity());
        return new DeductOutcome(false, "库存不足");
    }
}
