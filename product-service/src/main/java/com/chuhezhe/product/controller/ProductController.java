package com.chuhezhe.product.controller;

import com.chuhezhe.product.entity.Product;
import com.chuhezhe.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 商品微服务对外接口（US-018）。经网关访问前缀为 /product-service（discovery locator 按 serviceId 路由）。
 */
@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** 查询商品。 */
    @GetMapping("/products/{id}")
    public Product getById(@PathVariable Long id) {
        return productService.getById(id);
    }

    /** 改价。 */
    @PutMapping("/products/{id}")
    public int updatePrice(@PathVariable Long id, @RequestParam BigDecimal price) {
        return productService.updatePrice(id, price);
    }

    /** 扣库存（供 US-019 order-service 经 Feign 调用）。success=false 表示库存不足或商品不存在。 */
    @PostMapping("/products/{id}/deduct")
    public Map<String, Object> deductStock(@PathVariable Long id, @RequestParam int quantity) {
        boolean success = productService.deductStock(id, quantity);
        return Map.of("productId", id, "quantity", quantity, "success", success);
    }
}
