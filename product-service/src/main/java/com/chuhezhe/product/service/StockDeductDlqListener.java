package com.chuhezhe.product.service;

import com.chuhezhe.common.mq.MqConstants;
import com.chuhezhe.common.dto.StockDeductMsg;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.LongString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 扣库存死信队列监听器（US-023）。
 *
 * <p>消费 {@code stock.deduct.dlq} 中的死信消息，完成两件事：
 * <ol>
 *   <li><b>结构化日志</b>：打印 ERROR 级别日志，包含 orderNo、死信原因、x-death 元信息，
 *       便于 ELK/Loki 等日志平台的告警规则命中。</li>
 *   <li><b>邮件告警</b>：调用 {@link DlqAlertEmailService} 发送 HTML 告警邮件，
 *       通知值班人和研发负责人及时介入。</li>
 * </ol>
 *
 * <h3>x-death Headers 解析</h3>
 * <p>RabbitMQ 在消息进入死信队列时，会在消息头中追加 {@code x-death} 字段，其结构为：
 * <pre>
 * x-death: [
 *   {
 *     "count": 1,                           // 该队列死信次数
 *     "reason": "rejected",                 // 死信原因：rejected / expired / maxlen
 *     "queue": "stock.deduct.queue",        // 原始队列名
 *     "time": ...,
 *     "exchange": "order.saga.exchange",
 *     "routing-keys": ["stock.deduct"]
 *   }
 * ]
 * </pre>
 * 本监听器提取 {@code reason} 和 {@code count} 用于告警内容展示。
 *
 * <h3>消费策略</h3>
 * <p>DLQ 消息代表「已确认无法正常消费的毒消息」，不应再 requeue（否则会无限循环）：
 * <ul>
 *   <li>告警发送成功/失败均 basicAck，保证消息从 DLQ 消费完成，避免 DLQ 无限积压。</li>
 *   <li>消息体保留在日志中，运维可据日志手动补偿；若需持久化可扩展写入 DB 或告警记录表。</li>
 * </ul>
 *
 * <h3>Listener Container 配置</h3>
 * <p>DLQ 消费者使用独立的 {@code SimpleRabbitListenerContainerFactory}（在
 * {@link RabbitConfig} 中声明），与业务队列隔离，避免 DLQ 消费阻塞业务消费线程。
 */
@Component
public class StockDeductDlqListener {

    private static final Logger log = LoggerFactory.getLogger(StockDeductDlqListener.class);

    private final DlqAlertEmailService alertEmailService;

    public StockDeductDlqListener(DlqAlertEmailService alertEmailService) {
        this.alertEmailService = alertEmailService;
    }

    /**
     * 监听死信队列，containerFactory 指向 {@code dlqContainerFactory}（独立线程池，
     * 与业务消费者隔离，互不干扰）。
     */
    @RabbitListener(queues = MqConstants.STOCK_DEDUCT_DLQ,
                    containerFactory = "dlqContainerFactory")
    public void onDeadLetter(StockDeductMsg msg, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        String messageBody = extractBody(message);
        // orderNo 直接取自反序列化后的 POJO（与业务监听器一致，转换器 INFERRED 按参数类型推断）
        String orderNo = msg.getOrderNo() != null ? msg.getOrderNo() : "unknown";

        // 1. 解析 x-death headers，提取死信原因与 nack 次数
        DeathInfo deathInfo = extractDeathInfo(message);

        // 2. 结构化错误日志（便于日志平台告警规则命中）
        log.error("[DLQ] 消息进入死信队列 ───────────────────────────────────────────\n" +
                  "  队列     : {}\n" +
                  "  orderNo  : {}\n" +
                  "  死信原因 : {}\n" +
                  "  nack次数 : {}\n" +
                  "  消息体   : {}\n" +
                  "─────────────────────────────────────────────────────────────────",
                MqConstants.STOCK_DEDUCT_DLQ,
                orderNo,
                deathInfo.reason(),
                deathInfo.retryCount(),
                messageBody);

        // 3. 发送邮件告警（内部有冷却去重，不会因同一消息多次消费而刷屏）
        alertEmailService.sendAlert(
                orderNo,
                MqConstants.STOCK_DEDUCT_DLQ,
                deathInfo.reason(),
                messageBody,
                deathInfo.retryCount()
        );

        // 4. 无论告警是否成功，都 ack 消息（死信不再 requeue，避免无限循环）
        channel.basicAck(tag, false);
    }

    // ─────────────────────────────────────────────
    //  私有辅助方法
    // ─────────────────────────────────────────────

    /**
     * 从消息 body 字节数组中提取 UTF-8 字符串。
     * 若消息体为空则返回占位字符串，不抛异常。
     */
    private String extractBody(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return "<empty>";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 解析消息头中的 {@code x-death} 字段，提取死信原因与 nack 次数。
     *
     * <p>x-death 是一个 {@code List<Map>}，RabbitMQ 把最新一条死信记录 prepend 到队首，
     * 故取 {@code get(0)}（即最近一次死信记录）作为主因。
     */
    private DeathInfo extractDeathInfo(Message message) {
        String reason = "unknown";
        long retryCount = 1L;

        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        if (headers != null) {
            Object xDeath = headers.get("x-death");
            if (xDeath instanceof List<?> deathList && !deathList.isEmpty()) {
                Object first = deathList.get(0);
                if (first instanceof Map<?, ?> deathMap) {
                    // 提取 reason（可能是 String 或 LongString）
                    Object reasonObj = deathMap.get("reason");
                    if (reasonObj instanceof LongString ls) {
                        reason = ls.toString();
                    } else if (reasonObj instanceof String s) {
                        reason = s;
                    }
                    // 提取 count
                    Object countObj = deathMap.get("count");
                    if (countObj instanceof Long l) {
                        retryCount = l;
                    } else if (countObj instanceof Number n) {
                        retryCount = n.longValue();
                    }
                }
            }
        }

        return new DeathInfo(reason, retryCount);
    }

    /**
     * 死信信息值对象，封装 x-death 解析结果，在方法间传递。
     *
     * @param reason     死信原因（rejected / expired / maxlen）
     * @param retryCount 该消息在原始队列上累计 nack 次数
     */
    private record DeathInfo(String reason, long retryCount) {}
}
