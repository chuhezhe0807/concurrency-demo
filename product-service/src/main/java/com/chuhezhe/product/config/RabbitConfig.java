package com.chuhezhe.product.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
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
 * <p>
 * US-023 新增：{@link #dlqContainerFactory} —— DLQ 专用监听器容器工厂。
 * 与业务消费者（{@code stock.deduct.queue}）使用独立的线程池与配置，隔离故障域：
 * 即使 DLQ 消费阻塞也不会占用业务消费线程，反之亦然。
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

    /**
     * DLQ 专用监听器容器工厂（US-023）。
     *
     * <p>设计要点：
     * <ul>
     *   <li><b>线程池隔离</b>：concurrentConsumers=1 / maxConcurrentConsumers=2，DLQ 消息量通常极少，
     *       单线程足够；即使 DLQ 告警慢（SMTP 超时），也不会影响业务消费线程。</li>
     *   <li><b>手动 ack</b>：正常告警流程由 {@link com.chuhezhe.product.service.StockDeductDlqListener}
     *       显式 basicAck，处理完即从队列移除，不再 requeue，避免告警循环。</li>
     *   <li><b>defaultRequeueRejected=false</b>：兜住「监听方法之前」的异常——典型是消息体是坏 JSON、
     *       Jackson 转换失败导致方法根本进不去（此时拿不到 Channel，无法手动 ack）。容器捕获该异常后
     *       按此设置直接丢弃而非重投，避免毒消息在 DLQ 上无限重投打满日志/线程。</li>
     *
     *    注意:被丢弃的毒消息会彻底从 DLQ 移除(stock.deduct.dlq 没有再下一级 DLX)。本 demo 没问题;生产上若想保留这类「连 DLQ
     *   ▎ 都消费不了」的消息以便事后排查,可给 stock.deduct.dlq 再挂一个 parking-lot 队列。当前不需要就不加了。
     * </ul>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory dlqContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter);
        // 手动 ack：告警处理完成后由监听器显式 basicAck
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // 转换失败等「进不了监听方法」的异常：直接丢弃不重投，避免毒消息无限循环
        factory.setDefaultRequeueRejected(false);
        // DLQ 消息量少，1 个消费者即可；最多扩到 2 个应对突发积压
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        return factory;
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
