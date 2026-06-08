package com.chuhezhe.product.config;

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
 * US-022 可靠消息 Saga 的 RabbitMQ 拓扑（product-service 侧）。
 * <p>
 * 与 order-service 声明同名、同属性的拓扑（声明幂等），保证任一服务先启动都能把队列建好；
 * 属性不一致会触发 PRECONDITION_FAILED。product 消费 {@code stock.deduct.queue}、回传 {@code order.result.queue}。
 */
@Configuration
public class RabbitConfig {

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

    /** 见 order-service RabbitConfig：INFERRED 精度按监听方法参数类型反序列化，跨模块同构 DTO 互通。 */
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
        return template;
    }
}
