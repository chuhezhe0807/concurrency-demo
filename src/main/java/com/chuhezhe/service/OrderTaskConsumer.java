package com.chuhezhe.service;

import com.chuhezhe.config.RabbitConfig;
import com.chuhezhe.event.OrderPlacedEvent;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * US-015：RabbitMQ 消费者，异步处理下单非核心任务（仅 demo.optimize.mq=true 时加载）。
 * <p>
 * 手动 ack（application.yml: listener.simple.acknowledge-mode=manual）：业务跑成功才 basicAck，
 * broker 才删消息；处理中应用崩溃则消息未确认、会重新投递，不丢任务。再叠加 Redis 幂等键防重复处理
 * （手动 ack/重投/网络抖动都可能导致同一条消息被消费两次）。失败时 basicNack(requeue=false)，
 * 避免毒消息无限重投——生产环境应改投死信队列（DLQ）人工排查。
 */
@Component
@ConditionalOnProperty(name = "demo.optimize.mq", havingValue = "true")
public class OrderTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTaskConsumer.class);
    private static final String IDEMPOTENT_KEY_PREFIX = "order:processed:";

    private final SmsService smsService;
    private final EmailService emailService;
    private final PointsService pointsService;
    private final StringRedisTemplate redisTemplate;

    public OrderTaskConsumer(SmsService smsService,
                             EmailService emailService,
                             PointsService pointsService,
                             StringRedisTemplate redisTemplate) {
        this.smsService = smsService;
        this.emailService = emailService;
        this.pointsService = pointsService;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void onMessage(OrderPlacedEvent event, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            // 幂等：以 orderNo 为键 SETNX，已处理过则直接 ack 跳过，避免重复发短信/重复加积分。
            String key = IDEMPOTENT_KEY_PREFIX + event.orderNo();
            Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(1));
            if (Boolean.FALSE.equals(first)) {
                log.info("[MQ-CONSUME] orderNo={} 已处理过，幂等跳过", event.orderNo());
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("[MQ-CONSUME] 收到非核心任务 orderNo={}，开始处理", event.orderNo());
            smsService.send(event.userId(), event.orderNo());
            emailService.send(event.userId(), event.orderNo());
            pointsService.add(event.userId(), event.amount());

            channel.basicAck(deliveryTag, false);
            log.info("[MQ-CONSUME] orderNo={} 处理完成并已 ack", event.orderNo());
        } catch (Exception e) {
            log.error("[MQ-CONSUME] orderNo={} 处理失败，nack 不重回队列（生产应投 DLQ）", event.orderNo(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
