package com.chuhezhe.order.dto;

import java.math.BigDecimal;

/**
 * product-service GET /products/{id} 的响应映射（Feign 反序列化用）。
 * 只声明 order-service 需要的字段，缺省字段 Feign/Jackson 会忽略。
 */
public class ProductDTO {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer stock;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
