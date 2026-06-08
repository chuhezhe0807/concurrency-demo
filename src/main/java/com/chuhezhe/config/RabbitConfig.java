package com.chuhezhe.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * US-015：用 RabbitMQ 替代 @Async 承接下单非核心任务。
 * <p>
 * 仅在 demo.optimize.mq=true 时加载（默认关闭，基线无需 RabbitMQ 即可启动）。提供：
 * <ul>
 *   <li>持久化队列 + 持久化交换机 + 绑定：broker 重启后队列与消息不丢；</li>
 *   <li>Jackson2JsonMessageConverter：消息体用 JSON 序列化 OrderPlacedEvent，可读且跨语言；</li>
 *   <li>开启 publisher confirm + returns 的 RabbitTemplate：确认 broker 是否真正收下/可路由。</li>
 * </ul>
 * 配合 application.yml 的 publisher-confirm-type=correlated、publisher-returns=true、
 * listener.simple.acknowledge-mode=manual，构成「publisher confirm + 持久化 + 手动 ack」三层防丢。
 */
@Configuration
@ConditionalOnProperty(name = "demo.optimize.mq", havingValue = "true")
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_QUEUE = "order.non-core.queue";
    public static final String ORDER_ROUTING_KEY = "order.placed";

    /** durable=true：队列元数据持久化到磁盘，broker 重启后仍在。 */
    @Bean
    public Queue orderQueue() {
        return new Queue(ORDER_QUEUE, true);
    }

    /** durable=true：交换机持久化。 */
    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Binding orderBinding(@Qualifier("orderQueue") Queue orderQueue, @Qualifier("orderExchange") DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue).to(orderExchange).with(ORDER_ROUTING_KEY);
    }

    /** 消息体用 JSON。Spring Boot 会把该 Bean 同时注入 RabbitTemplate 与监听容器工厂。 */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 自定义 RabbitTemplate：JSON 转换器 + mandatory + confirm/returns 回调。
     * confirm 回调告知 broker 是否落账（防「以为发了其实没发」）；returns 回调在消息
     * 无法路由到任何队列时触发（防「发到了 broker 但没人收」）。
     */
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
