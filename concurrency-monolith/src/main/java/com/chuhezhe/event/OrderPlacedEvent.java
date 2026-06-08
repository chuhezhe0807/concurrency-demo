package com.chuhezhe.event;

import java.math.BigDecimal;

/**
 * 下单成功事件（US-014）。在下单事务里发布，由 @TransactionalEventListener(AFTER_COMMIT)
 * 在事务**提交后**才消费，触发短信/邮件/积分等非核心任务——核心回滚则不会触发。
 */
public record OrderPlacedEvent(Long userId, String orderNo, BigDecimal amount) {
}
