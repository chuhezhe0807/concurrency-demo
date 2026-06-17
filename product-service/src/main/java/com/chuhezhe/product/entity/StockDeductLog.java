package com.chuhezhe.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 扣库存幂等去重表（US-022）。以 order_no 为主键：消费扣库存消息前先 insert 占位，主键冲突=已处理过，
 * 直接跳过扣减。保证消息「至少一次」重投时，库存只被扣一次。
 * <p>
 * {@code deducted} 记录这条消息最终「是否真的扣减了库存」：insert 占位时为 false，扣减成功后置 true，
 * 库存不足则保持 false。DLQ 补偿（{@link com.chuhezhe.product.service.StockDeductDlqListener}）据此判定——
 * 不能只看「主键是否存在」，因为库存不足时占位行也在，但其实没扣。
 */
@TableName("t_stock_deduct_log")
public class StockDeductLog {

    @TableId
    private String orderNo;
    private Long productId;
    private Integer quantity;
    /** 是否真的扣减成功：占位 insert 时 false，扣成功后 true，库存不足保持 false。 */
    private Boolean deducted;
    private LocalDateTime createTime;

    public StockDeductLog() {
    }

    public StockDeductLog(String orderNo, Long productId, Integer quantity) {
        this.orderNo = orderNo;
        this.productId = productId;
        this.quantity = quantity;
        this.deducted = false;
        this.createTime = LocalDateTime.now();
    }

    public Boolean getDeducted() {
        return deducted;
    }

    public void setDeducted(Boolean deducted) {
        this.deducted = deducted;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
