package com.chuhezhe.order.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * US-022 可靠消息 Saga 的 RabbitMQ 拓扑（order-service 侧）。
 * <p>
 * 两条业务队列 + 一个死信队列，都 durable（broker 重启不丢）：
 * <ul>
 *   <li>{@code stock.deduct.queue}：order 发、product 收的「扣库存请求」。配死信交换机，消费失败 nack 后进 DLQ。</li>
 *   <li>{@code order.result.queue}：product 发、order 收的「扣库存结果」。</li>
 *   <li>{@code stock.deduct.dlq}：扣库存毒消息的死信队列，供补偿/排查。</li>
 * </ul>
 * order 与 product 两模块都声明同名拓扑（声明幂等），保证任一服务先启动都能把队列建好；属性必须一致否则报
 * PRECONDITION_FAILED。
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final String SAGA_EXCHANGE = "order.saga.exchange";
    public static final String DLX_EXCHANGE = "order.saga.dlx";

    public static final String STOCK_DEDUCT_QUEUE = "stock.deduct.queue";
    public static final String STOCK_DEDUCT_KEY = "stock.deduct";
    public static final String ORDER_RESULT_QUEUE = "order.result.queue";
    public static final String ORDER_RESULT_KEY = "order.result";
    public static final String STOCK_DEDUCT_DLQ = "stock.deduct.dlq";
    public static final String STOCK_DEDUCT_DLQ_KEY = "stock.deduct.dlq";

    @Bean
    public DirectExchange sagaExchange() {
        return new DirectExchange(SAGA_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange sagaDlx() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /** 扣库存队列：消费失败的消息死信到 DLX，避免毒消息无限重投。 */
    @Bean
    public Queue stockDeductQueue() {
        return QueueBuilder.durable(STOCK_DEDUCT_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(STOCK_DEDUCT_DLQ_KEY)
                .build();
    }

    @Bean
    public Queue orderResultQueue() {
        return QueueBuilder.durable(ORDER_RESULT_QUEUE).build();
    }

    @Bean
    public Queue stockDeductDlq() {
        return QueueBuilder.durable(STOCK_DEDUCT_DLQ).build();
    }

    @Bean
    public Binding stockDeductBinding() {
        return BindingBuilder.bind(stockDeductQueue()).to(sagaExchange()).with(STOCK_DEDUCT_KEY);
    }

    @Bean
    public Binding orderResultBinding() {
        return BindingBuilder.bind(orderResultQueue()).to(sagaExchange()).with(ORDER_RESULT_KEY);
    }

    @Bean
    public Binding stockDeductDlqBinding() {
        return BindingBuilder.bind(stockDeductDlq()).to(sagaDlx()).with(STOCK_DEDUCT_DLQ_KEY);
    }

    /**
     * JSON 消息转换器。type precedence 设为 INFERRED：忽略发送方写入的 {@code __TypeId__} 头
     * （那是 product-service 包名下的类，本模块没有），改按 @RabbitListener 方法参数类型反序列化，
     * 这样两个模块用各自的同构 DTO 也能正确收发。
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

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
