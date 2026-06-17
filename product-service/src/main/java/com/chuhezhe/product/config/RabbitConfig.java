package com.chuhezhe.product.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * product-service 侧的 RabbitMQ 配置。
 * <p>
 * 共享拓扑（交换机/队列/绑定）、名称常量与 JSON 转换器已下沉到 common 模块的
 * {@link com.chuhezhe.common.mq.RabbitTopologyConfig}（自动配置装配）。这里只保留 product 作为
 * <b>消费者</b>独有的两个 Bean：DLQ 专用监听容器工厂、以及回传结果用的 {@link RabbitTemplate}。
 */
@Configuration
public class RabbitConfig {

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

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter);
        return template;
    }
}
