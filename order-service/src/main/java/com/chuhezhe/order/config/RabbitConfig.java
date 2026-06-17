package com.chuhezhe.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * order-service 侧的 RabbitMQ 配置。
 * <p>
 * 共享拓扑（交换机/队列/绑定）、名称常量与 JSON 转换器已下沉到 common 模块的
 * {@link com.chuhezhe.common.mq.RabbitTopologyConfig}（自动配置装配）。这里只保留 order 作为
 * <b>生产者</b>独有的 {@link RabbitTemplate}：开启 mandatory + publisher confirm/return 回调，
 * 用于本地消息表轮询投递（{@code OutboxRelay}）的可靠投递确认。
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter);
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData == null ? "-" : correlationData.getId();
            if (ack) {
                log.info("[MQ-CONFIRM] broker 已确认收下消息 correlationId={}", id);
            } else {
                log.warn("[MQ-CONFIRM] broker 拒收消息 correlationId={} cause={}", id, cause);
            }
        });
        template.setReturnsCallback(returned ->
                log.warn("[MQ-RETURN] 消息无法路由被退回 exchange={} routingKey={} replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
