package com.chuhezhe.order.dto;

public class CreateOrderRequest {

    private Long userId;
    private Long productId;
    private Integer quantity;

    /**
     * US-021 失败注入：true 时在「扣库存 + 建单」都成功后故意抛异常，触发 Seata 全局回滚，
     * 用于演示跨服务强一致——product 已扣的库存会经 undo_log 还原、本地订单 insert 一并回滚。
     */
    private Boolean mockFail;

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

    public Boolean getMockFail() {
        return mockFail;
    }

    public void setMockFail(Boolean mockFail) {
        this.mockFail = mockFail;
    }
}
