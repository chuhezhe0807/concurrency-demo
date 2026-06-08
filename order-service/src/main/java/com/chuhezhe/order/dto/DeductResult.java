package com.chuhezhe.order.dto;

/**
 * product-service POST /products/{id}/deduct 的响应映射。success=false 表示库存不足或商品不存在。
 */
public class DeductResult {

    private Long productId;
    private Integer quantity;
    private Boolean success;

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

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }
}
