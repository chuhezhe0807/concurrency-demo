package com.chuhezhe.service;

import com.chuhezhe.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 下单非核心任务（短信/邮件/积分）的异步入口（US-014）。
 * <p>
 * 用 @TransactionalEventListener(AFTER_COMMIT) 监听 OrderPlacedEvent：只有下单事务**提交成功后**
 * 才触发——核心回滚则事件不投递，从根上避免「提交前/回滚后仍发通知」。再叠加 @Async("orderExecutor")
 * 让任务在线程池执行，下单主线程不必等待。
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        log.info("[ASYNC] 事务已提交，非核心任务进入线程池执行 orderNo={}", event.orderNo());
        smsService.send(event.userId(), event.orderNo());
        emailService.send(event.userId(), event.orderNo());
        pointsService.add(event.userId(), event.amount());
    }
}
