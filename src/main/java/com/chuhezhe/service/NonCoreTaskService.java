package com.chuhezhe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 下单非核心任务（短信/邮件/积分）的异步入口（US-014）。
 * <p>
 * 单独成 bean：@Async 靠 Spring 代理生效，必须跨 bean 调用（OrderService 内部自调用不会异步）。
 * runAsync 丢到 orderExecutor 线程池执行，下单主线程不必等它跑完即可返回。
 */
@Service
public class NonCoreTaskService {

    private static final Logger log = LoggerFactory.getLogger(NonCoreTaskService.class);

    private final SmsService smsService;
    private final EmailService emailService;
    private final PointsService pointsService;

    public NonCoreTaskService(SmsService smsService,
                              EmailService emailService,
                              PointsService pointsService) {
        this.smsService = smsService;
        this.emailService = emailService;
        this.pointsService = pointsService;
    }

    @Async("orderExecutor")
    public void runAsync(Long userId, String orderNo, BigDecimal amount) {
        log.info("[ASYNC] 非核心任务进入线程池执行 orderNo={}", orderNo);
        smsService.send(userId, orderNo);
        emailService.send(userId, orderNo);
        pointsService.add(userId, amount);
    }
}
