package com.chuhezhe.order.client;

import com.chuhezhe.order.dto.DeductResult;
import com.chuhezhe.order.dto.ProductDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调用 product-service 的声明式 Feign 客户端（US-019）。
 * <p>
 * name="product-service" 对应 Nacos 里的服务名，Feign 经 LoadBalancer 解析为某个实例地址，
 * 方法签名与 product-service 的 Controller 一一对应。跨进程网络调用替代了单体里的本地方法调用。
 */
@FeignClient(name = "product-service")
public interface ProductClient {

    @GetMapping("/products/{id}")
    ProductDTO getById(@PathVariable("id") Long id);

    @PostMapping("/products/{id}/deduct")
    DeductResult deductStock(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);
}
