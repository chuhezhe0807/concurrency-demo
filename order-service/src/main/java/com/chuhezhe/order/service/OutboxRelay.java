package com.chuhezhe.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chuhezhe.order.config.RabbitConfig;
import com.chuhezhe.order.dto.StockDeductMsg;
import com.chuhezhe.order.entity.OrderOutbox;
import com.chuhezhe.order.mapper.OrderOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地消息表轮询投递器（US-022）。把 t_order_outbox 里 status=NEW 的消息可靠地发到 RabbitMQ。
 * <p>
 * 为什么需要它：下单只把消息写进了本地表（与订单同事务，保证不丢），但还没真正发到 broker。
 * 这里定时扫描未发送的消息投递；publisher confirm 回来确认 broker 已收下后才置 SENT。没收到 confirm
 * 的消息下一轮会重发——「至少一次」投递，由消费端（product-service）的幂等表去重，保证只扣一次库存。
 * <p>
 * 这是「本地消息表」方案对比纯 publisher-confirm 的关键：即便进程在「写库成功、发消息前」崩溃，
 * 重启后轮询仍能把遗留的 NEW 消息补发出去，不依赖内存中的待发队列。
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OrderOutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OrderOutboxMapper outboxMapper, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /** 每 2 秒扫描一次待发送消息。生产可用更密集调度或事务提交后立即触发一次 + 轮询兜底。 */
    @Scheduled(fixedDelay = 2000)
    public void relay() {
        List<OrderOutbox> pending = outboxMapper.selectList(new LambdaQueryWrapper<OrderOutbox>()
                .eq(OrderOutbox::getStatus, OrderOutbox.STATUS_NEW)
                .orderByAsc(OrderOutbox::getId)
                .last("limit 50"));
        if (pending.isEmpty()) {
            return;
        }
        for (OrderOutbox outbox : pending) {
            publish(outbox);
        }
    }

    private void publish(OrderOutbox outbox) {
        // correlationData 关联 confirm 回调：以 outbox 主键标识，broker 确认后置 SENT。
        CorrelationData correlation = new CorrelationData(String.valueOf(outbox.getId()));
        correlation.getFuture().whenComplete((confirm, ex) -> {
            if (ex == null && confirm != null && confirm.isAck()) {
                markSent(outbox.getId());
                log.info("[OUTBOX] 投递确认成功，置 SENT orderNo={} outboxId={}", outbox.getOrderNo(), outbox.getId());
            } else {
                bumpRetry(outbox.getId());
                log.warn("[OUTBOX] 未收到 broker 确认，下一轮重发 orderNo={} outboxId={} cause={}",
                        outbox.getOrderNo(), outbox.getId(), ex == null ? confirm : ex.getMessage());
            }
        });
        // payload 存的是 JSON，先还原成对象再发，交给 Jackson2JsonMessageConverter 生成规范 JSON 消息，
        // 避免直接发字符串被二次转义；消费端按 StockDeductMsg 反序列化。
        StockDeductMsg msg = readMsg(outbox.getPayload());
        rabbitTemplate.convertAndSend(RabbitConfig.SAGA_EXCHANGE, RabbitConfig.STOCK_DEDUCT_KEY,
                msg, correlation);
        log.info("[OUTBOX] 已投递扣库存消息 orderNo={} -> {}/{}",
                outbox.getOrderNo(), RabbitConfig.SAGA_EXCHANGE, RabbitConfig.STOCK_DEDUCT_KEY);
    }

    private StockDeductMsg readMsg(String json) {
        try {
            return objectMapper.readValue(json, StockDeductMsg.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化扣库存消息失败: " + json, e);
        }
    }

    private void markSent(Long id) {
        OrderOutbox update = new OrderOutbox();
        update.setId(id);
        update.setStatus(OrderOutbox.STATUS_SENT);
        outboxMapper.updateById(update);
    }

    private void bumpRetry(Long id) {
        OrderOutbox current = outboxMapper.selectById(id);
        if (current == null) {
            return;
        }
        OrderOutbox update = new OrderOutbox();
        update.setId(id);
        update.setRetryCount(current.getRetryCount() == null ? 1 : current.getRetryCount() + 1);
        outboxMapper.updateById(update);
    }
}
