package com.chuhezhe.dto;

public class OrderResult {

    private Long orderId;
    private String orderNo;
    private long elapsedMs;

    public OrderResult(Long orderId, String orderNo, long elapsedMs) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.elapsedMs = elapsedMs;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
