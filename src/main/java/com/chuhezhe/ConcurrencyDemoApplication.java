package com.chuhezhe;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.chuhezhe.mapper")
public class ConcurrencyDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConcurrencyDemoApplication.class, args);
    }
}
