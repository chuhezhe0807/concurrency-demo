package com.chuhezhe.controller;

import com.chuhezhe.entity.Product;
import com.chuhezhe.service.CacheRaceDemoService;
import com.chuhezhe.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 商品查询/改价接口（US-012 缓存 / US-013 一致性演示）。
 */
@RestController
public class ProductController {

    private final ProductService productService;
    private final CacheRaceDemoService cacheRaceDemoService;

    public ProductController(ProductService productService,
                             CacheRaceDemoService cacheRaceDemoService) {
        this.productService = productService;
        this.cacheRaceDemoService = cacheRaceDemoService;
    }

    /** 查询商品（cache 开关开启时走 Redis）。 */
    @GetMapping("/products/{id}")
    public Product getById(@PathVariable Long id) {
        return productService.getById(id);
    }

    /** 改价：@CacheEvict 改库后清缓存（US-013 正确写法）。 */
    @PutMapping("/products/{id}")
    public int updatePrice(@PathVariable Long id, @RequestParam BigDecimal price) {
        return productService.updatePrice(id, price);
    }

    /** US-013 竞态演示：触发「写删缓存+改库未提交 / 读回填旧值」竞态，返回观测结果。 */
    @PostMapping("/products/{id}/stale-race")
    public Map<String, Object> staleRace(@PathVariable Long id,
                                         @RequestParam BigDecimal newPrice) throws InterruptedException {
        return cacheRaceDemoService.run(id, newPrice);
    }
}
