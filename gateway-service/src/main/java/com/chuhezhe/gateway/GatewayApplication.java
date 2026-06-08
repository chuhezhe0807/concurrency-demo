package com.chuhezhe.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阶段二 API 网关（US-017）。
 * <p>
 * Spring Cloud Gateway 作为所有微服务的统一入口：启动后注册到 Nacos，并借助
 * DiscoveryClient 按 serviceId 自动路由到下游服务（US-018 起的 product-service / order-service）。
 * 服务发现由 spring-cloud-starter-alibaba-nacos-discovery 自动装配，无需显式 @EnableDiscoveryClient。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
