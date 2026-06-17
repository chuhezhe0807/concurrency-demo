package com.chuhezhe.product.service;

import com.chuhezhe.common.mq.MqConstants;
import com.chuhezhe.common.dto.OrderResultMsg;
import com.chuhezhe.common.dto.StockDeductMsg;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 扣库存消息消费者（US-022）。消费 order-service 经本地消息表可靠投递来的扣库存请求：
 * 幂等扣减 → 把结果回传到 {@code order.result.queue} → 手动 ack。
 * <p>
 * 手动 ack 的两条路径：
 * <ul>
 *   <li>业务正常（无论库存充足与否，都是确定结果）：发结果消息 + basicAck，消息消费完成。</li>
 *   <li>处理异常（如 DB 抖动）：basicNack(requeue=false) 进死信队列，避免毒消息无限重投；
 *       真正的「不丢」由 order-service 的本地消息表轮询重发保证。</li>
 * </ul>
 */
@Component
public class StockDeductListener {

    private static final Logger log = LoggerFactory.getLogger(StockDeductListener.class);

    private final ProductService productService;
    private final RabbitTemplate rabbitTemplate;

    /**
     * 模拟异常开关（演示 DLQ + 邮件告警）。配置 {@code stock.mock-fail=true} 时，
     * 消费扣库存消息会直接抛异常 → nack 进死信队列，触发告警链路。
     * 默认 false（正常业务），演示完无需改代码、改配置即可恢复。
     */
    @Value("${stock.mock-fail:false}")
    private boolean mockFail;

    public StockDeductListener(ProductService productService, RabbitTemplate rabbitTemplate) {
        this.productService = productService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = MqConstants.STOCK_DEDUCT_QUEUE)
    public void onStockDeduct(StockDeductMsg msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("[STOCK-CONSUME] 收到扣库存消息 orderNo={} productId={} qty={}",
                    msg.getOrderNo(), msg.getProductId(), msg.getQuantity());
            if (mockFail) {
                throw new RuntimeException("模拟扣库存异常（演示 DLQ + 邮件告警）");
            }
            ProductService.DeductOutcome outcome = productService.deductForOrder(msg);
            OrderResultMsg result = new OrderResultMsg(msg.getOrderNo(), outcome.success(), outcome.reason());
            rabbitTemplate.convertAndSend(MqConstants.SAGA_EXCHANGE, MqConstants.ORDER_RESULT_KEY, result);
            log.info("[STOCK-CONSUME] 已回传结果 orderNo={} success={}", msg.getOrderNo(), outcome.success());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[STOCK-CONSUME] 处理异常，nack 进死信队列 orderNo={}", msg.getOrderNo(), e);
            channel.basicNack(tag, false, false);
        }
    }
}
