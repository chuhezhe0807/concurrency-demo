package com.chuhezhe.product.dto;

/**
 * 扣库存结果消息（US-022）。product-service 处理完回传，order-service 据此确认/取消订单。
 * 与 order-service 同名 DTO 字段一致，跨模块按字段名 JSON 序列化（不共享类）。
 */
public class OrderResultMsg {

    private String orderNo;
    private boolean success;
    private String reason;

    public OrderResultMsg() {
    }

    public OrderResultMsg(String orderNo, boolean success, String reason) {
        this.orderNo = orderNo;
        this.success = success;
        this.reason = reason;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
