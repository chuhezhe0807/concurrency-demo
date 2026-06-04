package com.chuhezhe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** 模拟发邮件，耗时约 300ms（非核心逻辑）。 */
    public void send(Long userId, String orderNo) {
        sleep(300);
        log.info("[EMAIL] 已向用户 {} 发送订单 {} 的邮件确认", userId, orderNo);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
