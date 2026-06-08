package com.chuhezhe.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 商品微服务（US-018）。
 * <p>
 * 阶段一单体里的商品逻辑被拆出为独立可部署服务：独立连接 MySQL（t_product 表），
 * 启动后注册到 Nacos，经网关按 serviceId(product-service) 路由。US-019 起 order-service
 * 通过 OpenFeign 调用本服务的扣库存接口完成跨服务下单。
 */
@SpringBootApplication
@MapperScan("com.chuhezhe.product.mapper")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
