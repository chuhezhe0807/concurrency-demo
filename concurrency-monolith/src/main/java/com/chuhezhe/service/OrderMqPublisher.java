package com.chuhezhe.service;

import com.chuhezhe.config.RabbitConfig;
import com.chuhezhe.event.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * US-015：把下单非核心任务投递到 RabbitMQ（仅 demo.optimize.mq=true 时加载）。
 * <p>
 * 同样用 @TransactionalEventListener(AFTER_COMMIT)：下单事务**提交后**才投递，核心回滚则不发——
 * 与 US-014 的 @Async 一致，避免「提交前/回滚后误发」。区别在于落点是 broker 而非进程内线程池：
 * 任务被持久化到 RabbitMQ，应用重启也不丢，由独立消费者异步处理。
 */
@Component
@ConditionalOnProperty(name = "demo.optimize.mq", havingValue = "true")
public class OrderMqPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderMqPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public OrderMqPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        // 用 orderNo 作为 correlationId：confirm 回调与下游幂等都以它为准。
        CorrelationData correlation = new CorrelationData(event.orderNo());
        rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_ROUTING_KEY, event, correlation);
        log.info("[MQ] 事务已提交，非核心任务投递到 RabbitMQ orderNo={}", event.orderNo());
    }
}
