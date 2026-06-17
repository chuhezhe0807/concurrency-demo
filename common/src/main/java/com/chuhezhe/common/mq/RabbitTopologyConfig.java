package com.chuhezhe.common.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * US-022 可靠消息 Saga 的 RabbitMQ 拓扑（order/product 共享）。
 * <p>
 * 两条业务队列 + 一个死信队列，都 durable（broker 重启不丢）：
 * <ul>
 *   <li>{@code stock.deduct.queue}：order 发、product 收的「扣库存请求」。配死信交换机，消费失败 nack 后进 DLQ。</li>
 *   <li>{@code order.result.queue}：product 发、order 收的「扣库存结果」。</li>
 *   <li>{@code stock.deduct.dlq}：扣库存毒消息的死信队列，供补偿/排查。</li>
 * </ul>
 * 两服务原本各声明一份同名同属性的拓扑（声明幂等），现下沉到公共模块统一定义，杜绝属性漂移导致的
 * PRECONDITION_FAILED。
 * <p>
 * <b>为何用 {@link AutoConfiguration} 而非普通 {@code @Configuration}</b>：两个主类只扫描各自子包
 * （{@code com.chuhezhe.order} / {@code com.chuhezhe.product}），不会扫到本模块的 {@code com.chuhezhe.common}。
 * 注册为自动配置（见 {@code META-INF/spring/...AutoConfiguration.imports}）后，服务只要依赖 common 即自动装配，
 * 无需改主类扫描包。各服务独有的 Bean（order 的带 confirm 回调的 RabbitTemplate、product 的 dlqContainerFactory）
 * 仍留在各自的 RabbitConfig 中。
 */
@AutoConfiguration
public class RabbitTopologyConfig {

    @Bean
    public DirectExchange sagaExchange() {
        return new DirectExchange(MqConstants.SAGA_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange sagaDlx() {
        return new DirectExchange(MqConstants.DLX_EXCHANGE, true, false);
    }

    /** 扣库存队列：消费失败的消息死信到 DLX，避免毒消息无限重投。 */
    @Bean
    public Queue stockDeductQueue() {
        return QueueBuilder.durable(MqConstants.STOCK_DEDUCT_QUEUE)
                .deadLetterExchange(MqConstants.DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.STOCK_DEDUCT_DLQ_KEY)
                .build();
    }

    @Bean
    public Queue orderResultQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_RESULT_QUEUE).build();
    }

    @Bean
    public Queue stockDeductDlq() {
        return QueueBuilder.durable(MqConstants.STOCK_DEDUCT_DLQ).build();
    }

    @Bean
    public Binding stockDeductBinding() {
        return BindingBuilder.bind(stockDeductQueue()).to(sagaExchange()).with(MqConstants.STOCK_DEDUCT_KEY);
    }

    @Bean
    public Binding orderResultBinding() {
        return BindingBuilder.bind(orderResultQueue()).to(sagaExchange()).with(MqConstants.ORDER_RESULT_KEY);
    }

    @Bean
    public Binding stockDeductDlqBinding() {
        return BindingBuilder.bind(stockDeductDlq()).to(sagaDlx()).with(MqConstants.STOCK_DEDUCT_DLQ_KEY);
    }

    /**
     * JSON 消息转换器。type precedence 保持 INFERRED：忽略发送方写入的 {@code __TypeId__} 头，
     * 改按 {@code @RabbitListener} 方法参数类型反序列化。
     * <p>
     * DTO 已下沉到 common 共享同一个 FQCN，{@code __TypeId__} 在两边都能解析，理论上可改回默认（TYPE_ID）。
     * 之所以仍用 INFERRED：默认按头部类名反序列化时，{@code DefaultJackson2JavaTypeMapper} 需额外配置
     * trustedPackages 才放行，否则抛 MessageConversionException；而 INFERRED 按监听方法签名定型，无需该配置、
     * 也不受发送方头部影响，更省事、更健壮。
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
