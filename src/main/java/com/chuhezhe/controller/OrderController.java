package com.chuhezhe.controller;

import com.chuhezhe.service.OrderService;
import com.chuhezhe.vo.OrderVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders")
    public List<OrderVO> listOrders(@RequestParam(defaultValue = "1") long pageNo,
                                    @RequestParam(defaultValue = "20") long pageSize) {
        return orderService.listOrders(pageNo, pageSize);
    }
}
