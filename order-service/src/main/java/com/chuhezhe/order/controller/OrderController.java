package com.chuhezhe.order.controller;

import com.chuhezhe.order.dto.CreateOrderRequest;
import com.chuhezhe.order.dto.OrderResult;
import com.chuhezhe.order.service.OrderService;
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

    @PostMapping("/orders")
    public OrderResult createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.placeOrder(request);
    }
}
