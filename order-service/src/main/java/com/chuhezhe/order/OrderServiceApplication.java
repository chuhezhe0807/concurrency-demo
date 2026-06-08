package com.chuhezhe.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单微服务（US-019）。
 * <p>
 * 注册到 Nacos；下单时核心的「扣库存」不再本地查表，而是通过 OpenFeign 跨服务调用 product-service。
 * @EnableFeignClients 开启声明式客户端扫描，@MapperScan 扫描订单表 Mapper。
 * @EnableScheduling 开启定时任务（US-022 本地消息表 outbox 轮询投递）。
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@MapperScan("com.chuhezhe.order.mapper")
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
