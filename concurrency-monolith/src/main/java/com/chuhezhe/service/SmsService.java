package com.chuhezhe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    /** 模拟发短信，耗时约 500ms（非核心逻辑）。 */
    public void send(Long userId, String orderNo) {
        sleep(500);
        log.info("[SMS] 已向用户 {} 发送订单 {} 的短信通知", userId, orderNo);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
