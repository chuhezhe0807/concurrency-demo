package com.chuhezhe.order.service;

import com.chuhezhe.common.mq.MqConstants;
import com.chuhezhe.common.dto.OrderResultMsg;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 扣库存结果消费者（US-022）。消费 product-service 回传到 {@code order.result.queue} 的结果，
 * 把订单从「待确认」推进到「已确认」或「已取消」（补偿）。
 * <p>
 * 手动 ack：业务处理成功才 basicAck；处理出错则 basicNack(requeue=true) 让消息重投，配合
 * {@link OrderService#applyDeductResult} 的状态幂等（仅推进待确认订单），重复投递不会改坏终态。
 */
@Component
public class OrderResultListener {

    private static final Logger log = LoggerFactory.getLogger(OrderResultListener.class);

    private final OrderService orderService;

    public OrderResultListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = MqConstants.ORDER_RESULT_QUEUE)
    public void onResult(OrderResultMsg result, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("[ORDER-RESULT] 收到扣库存结果 orderNo={} success={} reason={}",
                    result.getOrderNo(), result.isSuccess(), result.getReason());
            orderService.applyDeductResult(result.getOrderNo(), result.isSuccess(), result.getReason());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[ORDER-RESULT] 处理结果失败，重新入队 orderNo={}", result.getOrderNo(), e);
            channel.basicNack(tag, false, true);
        }
    }
}
