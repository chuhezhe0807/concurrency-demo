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
 *   <li>处理异常（如 DB 抖动）：先在本次投递内<b>重试</b> {@link #MAX_ATTEMPTS} 次（线性退避），
 *       救活瞬时故障；仍失败才 basicNack(requeue=false) 进死信队列，由 DLQ 监听器查幂等表补发结果。</li>
 * </ul>
 * 重试是安全的：{@link ProductService#deductForOrder} 以 order_no 幂等去重，重投/重试都只扣一次库存。
 */
@Component
public class StockDeductListener {

    private static final Logger log = LoggerFactory.getLogger(StockDeductListener.class);

    /** 异常时本次投递内的最大尝试次数（含首次）。超过才进 DLQ。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 重试线性退避基数（毫秒）：第 n 次失败后睡 n * BACKOFF。 */
    private static final long RETRY_BACKOFF_MS = 200;

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
        for (int attempt = 1; ; attempt++) {
            try {
                log.info("[STOCK-CONSUME] 收到扣库存消息 orderNo={} productId={} qty={} attempt={}/{}",
                        msg.getOrderNo(), msg.getProductId(), msg.getQuantity(), attempt, MAX_ATTEMPTS);
                if (mockFail) {
                    throw new RuntimeException("模拟扣库存异常（演示重试 + DLQ + 邮件告警）");
                }
                ProductService.DeductOutcome outcome = productService.deductForOrder(msg);
                OrderResultMsg result = new OrderResultMsg(msg.getOrderNo(), outcome.success(), outcome.reason());
                rabbitTemplate.convertAndSend(MqConstants.SAGA_EXCHANGE, MqConstants.ORDER_RESULT_KEY, result);
                log.info("[STOCK-CONSUME] 已回传结果 orderNo={} success={}", msg.getOrderNo(), outcome.success());
                channel.basicAck(tag, false);
                return;
            } catch (Exception e) {
                if (attempt < MAX_ATTEMPTS) {
                    log.warn("[STOCK-CONSUME] 处理失败，准备第 {} 次尝试 orderNo={} cause={}",
                            attempt + 1, msg.getOrderNo(), e.toString());
                    sleepBackoff(attempt);
                    // 继续下一轮重试；deductForOrder 幂等，重试不会重复扣减
                } else {
                    log.error("[STOCK-CONSUME] 重试 {} 次仍失败，nack 进死信队列 orderNo={}",
                            MAX_ATTEMPTS, msg.getOrderNo(), e);
                    channel.basicNack(tag, false, false);
                    return;
                }
            }
        }
    }

    /** 线性退避睡眠；被中断则恢复中断标志并提前结束本次等待。 */
    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
