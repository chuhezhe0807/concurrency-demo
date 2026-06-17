package com.chuhezhe.common.dto;

import java.math.BigDecimal;

/**
 * 扣库存消息（US-022）。order-service 写入本地消息表、投递到 RabbitMQ；product-service 消费。
 * <p>
 * 原本 order/product 各有一份同构副本（靠按字段名 JSON 反序列化互通），现下沉到 common 共享同一个类。
 */
public class StockDeductMsg {

    private String orderNo;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal amount;

    public StockDeductMsg() {
    }

    public StockDeductMsg(String orderNo, Long userId, Long productId, Integer quantity, BigDecimal amount) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amount = amount;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
