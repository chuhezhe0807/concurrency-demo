package com.chuhezhe.common.mq;

/**
 * US-022 可靠消息 Saga 的 RabbitMQ 名称常量（order/product 共享）。
 * <p>
 * 抽到公共模块的原因：交换机、队列、路由键名称是跨服务的「契约」，两边必须逐字一致，
 * 否则声明拓扑时属性不符会触发 PRECONDITION_FAILED、或消息路由不到队列。集中一处定义，
 * 避免两个服务各写一份 String 字面量而漂移。
 * <p>
 * 全部为编译期常量（{@code public static final String}），可直接用在 {@code @RabbitListener(queues = ...)} 注解上。
 */
public final class MqConstants {

    private MqConstants() {
    }

    public static final String SAGA_EXCHANGE = "order.saga.exchange";
    public static final String DLX_EXCHANGE = "order.saga.dlx";

    public static final String STOCK_DEDUCT_QUEUE = "stock.deduct.queue";
    public static final String STOCK_DEDUCT_KEY = "stock.deduct";
    public static final String ORDER_RESULT_QUEUE = "order.result.queue";
    public static final String ORDER_RESULT_KEY = "order.result";
    public static final String STOCK_DEDUCT_DLQ = "stock.deduct.dlq";
    public static final String STOCK_DEDUCT_DLQ_KEY = "stock.deduct.dlq";
}
