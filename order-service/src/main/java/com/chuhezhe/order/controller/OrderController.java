package com.chuhezhe.order.controller;

import com.chuhezhe.order.dto.CreateOrderRequest;
import com.chuhezhe.order.dto.OrderResult;
import com.chuhezhe.order.entity.Order;
import com.chuhezhe.order.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单微服务接口（US-019）。经网关访问前缀 /order-service（discovery locator 按 serviceId 路由）。
 */
@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** US-021 Seata 强一致下单：同步 Feign 扣库存 + 建单，全局事务统一提交/回滚。 */
    @PostMapping("/orders")
    public OrderResult createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.placeOrder(request);
    }

    /**
     * US-022 可靠消息最终一致下单：本地事务写订单(待确认)+本地消息表，立即返回；
     * 扣库存经 RabbitMQ 异步完成后回传结果，再确认或补偿取消订单。
     */
    @PostMapping("/orders/reliable")
    public OrderResult createOrderReliable(@RequestBody CreateOrderRequest request) {
        return orderService.placeOrderReliable(request);
    }

    /** 查订单状态：0=待确认 1=已确认 2=已取消。用于观察可靠消息异步推进后的终态。 */
    @GetMapping("/orders/{orderNo}")
    public Order getByOrderNo(@PathVariable String orderNo) {
        return orderService.getByOrderNo(orderNo);
    }
}
