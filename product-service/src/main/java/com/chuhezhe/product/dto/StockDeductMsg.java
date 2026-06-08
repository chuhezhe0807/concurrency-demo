package com.chuhezhe.product.dto;

import java.math.BigDecimal;

/**
 * 扣库存消息（US-022）。order-service 投递、product-service 消费。
 * 与 order-service 同名 DTO 字段一致，跨模块按字段名 JSON 反序列化（不共享类）。
 */
public class StockDeductMsg {

    private String orderNo;
    private Long userId;
    private Long productId;
    private Integer quantity;
    private BigDecimal amount;

    public StockDeductMsg() {
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
