package com.chuhezhe.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * HikariCP 连接池调优开关（US-011）。
 * <p>
 * false（基线）：沿用 application.yml 的 spring.datasource.hikari（10 连接 / 30s 超时）。
 * true（调优）：覆盖为 maximum-pool-size=30、minimum-idle=10、connection-timeout=3000。
 * <p>
 * 在 DataSource 完成属性绑定、连接池首次取连接（懒启动）之前覆盖参数，因此无需自定义 DataSource Bean。
 * 用 Environment 直接读开关，避免对 OptimizeProperties 形成 BeanPostProcessor 的早期依赖。
 */
@Component
public class HikariTuningPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HikariTuningPostProcessor.class);

    private final Environment environment;

    public HikariTuningPostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof HikariDataSource ds
                && environment.getProperty("demo.optimize.hikari-tuning", Boolean.class, false)) {
            ds.setMaximumPoolSize(30);
            ds.setMinimumIdle(10);
            ds.setConnectionTimeout(3000);
            log.info("HikariCP 调优开关已开启 -> maximumPoolSize=30, minimumIdle=10, connectionTimeout=3000ms");
        }
        return bean;
    }
}
