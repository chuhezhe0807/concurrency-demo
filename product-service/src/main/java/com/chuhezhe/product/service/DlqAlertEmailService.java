package com.chuhezhe.product.service;

import com.chuhezhe.product.config.DlqAlertProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DLQ 邮件告警服务（US-023）。
 *
 * <p>职责：当 {@code stock.deduct.dlq} 收到死信消息时，组装 HTML 告警邮件并发送给运维/研发。
 *
 * <h3>冷却去重机制</h3>
 * <p>同一 {@code orderNo} 频繁进入 DLQ（如 broker 重启导致同一条消息多次投递）时，
 * 若不加限制则每次都发邮件，造成邮件轰炸。冷却机制：
 * <pre>
 *   本地 ConcurrentHashMap<orderNo, 上次告警时间戳(ms)>
 *   ├── 首次进入 → 立即发送 + 记录时间戳
 *   ├── 冷却窗口内再次进入 → 跳过发送，只打 WARN 日志
 *   └── 超出冷却窗口后再次进入 → 重新发送 + 刷新时间戳
 * </pre>
 * <p>冷却 Map 仅存活于进程内存，服务重启后自动清空；窗口时长由
 * {@link DlqAlertProperties#getCooldownSeconds()} 控制（默认 300 秒）。
 *
 * <h3>告警邮件内容</h3>
 * <ul>
 *   <li>队列名、死信原因（x-death headers）</li>
 *   <li>消息体（JSON 原文）</li>
 *   <li>首次进入时间、当前告警时间</li>
 *   <li>建议处理步骤，引导排查方向</li>
 * </ul>
 */
@Service
public class DlqAlertEmailService {

    private static final Logger log = LoggerFactory.getLogger(DlqAlertEmailService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;
    private final DlqAlertProperties props;

    /**
     * 冷却窗口记录表：key=orderNo，value=上次成功发送告警的时间戳（毫秒）。
     * ConcurrentHashMap 保证多线程（多 consumer 线程并发消费 DLQ）下的安全读写。
     */
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public DlqAlertEmailService(JavaMailSender mailSender, DlqAlertProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /**
     * 发送 DLQ 告警邮件（入口方法，由 {@link StockDeductDlqListener} 调用）。
     *
     * @param orderNo      死信消息对应的订单号（用于去重冷却）
     * @param dlqName      死信队列名称
     * @param deathReason  x-death 中解析出的死信原因（rejected / expired / maxlen 等）
     * @param messageBody  消息体 JSON 原文（用于邮件展示）
     * @param retryCount   该消息累计被 nack 的次数（x-death count）
     */
    public void sendAlert(String orderNo, String dlqName,
                          String deathReason, String messageBody, long retryCount) {
        // 1. 告警开关
        if (!props.isEnabled()) {
            log.debug("[DLQ-ALERT] 告警已关闭，跳过发送 orderNo={}", orderNo);
            return;
        }

        // 2. 收件人不能为空
        if (props.getTo() == null || props.getTo().isEmpty()) {
            log.warn("[DLQ-ALERT] 收件人列表为空，请检查 dlq.alert.to 配置");
            return;
        }

        // 3. 冷却去重：用 compute 原子地「检查 + 抢占式占坑」，避免多 consumer 线程
        //    并发对同一 orderNo 各发一封（check-then-put 非原子的竞态）。
        // get ——> 判断 ——> put 非原子操作，RibbitConfig@dlqContainerFactory factory.setMaxConcurrentConsumers(2);
        // 最多可能有两个线程同时处理消息，造成同一个 orderNo 重复告警。所以改为 compute 避免多 consumer 线程。
        long now = System.currentTimeMillis();
        long cooldownMs = props.getCooldownSeconds() * 1000L;
        Long previous = cooldownMap.compute(orderNo, (k, lastSent) -> {
            if (lastSent != null && (now - lastSent) < cooldownMs) {
                return lastSent;   // 冷却窗口内：保持原时间戳，本次不发
            }
            return now;            // 抢占式占坑，确保同一时刻只有一个线程拿到发送权
        });
        if (previous != null && previous != now) {
            log.warn("[DLQ-ALERT] orderNo={} 在冷却窗口内（{}s），跳过重复告警，距上次告警 {}s",
                    orderNo, props.getCooldownSeconds(), (now - previous) / 1000);
            return;
        }

        // 顺手惰性清理过期条目，避免 cooldownMap 随唯一 orderNo 增长而内存泄漏
        evictExpired(now, cooldownMs);

        // 4. 组装并发送邮件
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");

            helper.setFrom(props.getFrom());
            helper.setTo(props.getTo().toArray(new String[0]));
            helper.setSubject(buildSubject(orderNo));
            helper.setText(buildHtmlBody(orderNo, dlqName, deathReason, messageBody, retryCount), true);

            mailSender.send(mime);

            // 5. 时间戳已在第 3 步 compute 时占坑，发送成功无需再写
            log.info("[DLQ-ALERT] 告警邮件已发送 orderNo={} to={}", orderNo, props.getTo());

        } catch (MessagingException | MailException e) {
            // 发送失败：回滚占坑，让冷却窗口内的后续死信仍有机会重新告警。
            // 邮件失败不影响 DLQ 消息的 ack，仅记录错误日志。
            cooldownMap.remove(orderNo, now);
            log.error("[DLQ-ALERT] 告警邮件发送失败 orderNo={}", orderNo, e);
        }
    }

    /** 清理已过冷却窗口的条目，防止 cooldownMap 随唯一 orderNo 持续增长。 */
    private void evictExpired(long now, long cooldownMs) {
        cooldownMap.entrySet().removeIf(e -> (now - e.getValue()) >= cooldownMs);
    }

    // ─────────────────────────────────────────────
    //  私有辅助方法
    // ─────────────────────────────────────────────

    private String buildSubject(String orderNo) {
        return props.getSubjectPrefix() + " | orderNo=" + orderNo
                + " | " + LocalDateTime.now().format(FMT);
    }

    /**
     * 构建 HTML 格式的告警邮件正文。
     * 使用内联 CSS 保证在大多数邮件客户端中正常渲染（不依赖外部样式表）。
     */
    private String buildHtmlBody(String orderNo, String dlqName,
                                  String deathReason, String messageBody, long retryCount) {
        String alertTime = LocalDateTime.now().format(FMT);
        String safeOrderNo = escapeHtml(orderNo);   // orderNo 同样转义，防止特殊字符破坏 HTML 结构
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"/></head>
                <body style="font-family:Arial,sans-serif;font-size:14px;color:#333;margin:0;padding:20px;">
                
                  <div style="border-left:4px solid #e74c3c;padding:12px 20px;background:#fff5f5;margin-bottom:20px;">
                    <h2 style="margin:0 0 6px;color:#e74c3c;">⚠️ 死信队列告警</h2>
                    <p style="margin:0;color:#666;">消息消费失败，已进入死信队列，请及时排查！</p>
                  </div>
                
                  <table style="border-collapse:collapse;width:100%%;margin-bottom:20px;">
                    <tr style="background:#f8f9fa;">
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;width:160px;">告警时间</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;">服务名</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;">product-service</td>
                    </tr>
                    <tr style="background:#f8f9fa;">
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;">死信队列</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;color:#e74c3c;font-weight:bold;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;">订单号</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-family:monospace;">%s</td>
                    </tr>
                    <tr style="background:#f8f9fa;">
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;">死信原因</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;color:#e67e22;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;font-weight:bold;">累计 nack 次数</td>
                      <td style="padding:8px 12px;border:1px solid #dee2e6;">%d</td>
                    </tr>
                  </table>
                
                  <div style="margin-bottom:20px;">
                    <p style="font-weight:bold;margin-bottom:6px;">📦 消息体（JSON）：</p>
                    <pre style="background:#f4f4f4;border:1px solid #ddd;padding:12px;border-radius:4px;
                                overflow-x:auto;font-size:13px;line-height:1.6;">%s</pre>
                  </div>
                
                  <div style="background:#fff8e1;border:1px solid #ffe082;padding:12px 16px;border-radius:4px;margin-bottom:20px;">
                    <p style="font-weight:bold;margin:0 0 8px;">🔍 建议排查步骤：</p>
                    <ol style="margin:0;padding-left:20px;line-height:2;">
                      <li>登录 RabbitMQ Management UI，查看 <code>%s</code> 队列中的原始消息详情及 x-death headers。</li>
                      <li>检查 product-service 日志，搜索 <code>orderNo=%s</code> 关键字，定位 nack 时的异常栈。</li>
                      <li>确认 MySQL / product 库存表状态是否异常（连接池耗尽、锁等待超时等）。</li>
                      <li>若为瞬时抖动（DB 抖动/网络闪断），可在 RabbitMQ UI 手动 requeue 该消息重新消费。</li>
                      <li>若为业务逻辑问题，修复后手动从 DLQ 转发，或通过 order-service outbox 重试补偿。</li>
                    </ol>
                  </div>
                
                  <hr style="border:none;border-top:1px solid #eee;margin:20px 0;"/>
                  <p style="color:#999;font-size:12px;margin:0;">
                    此邮件由 product-service DLQ 监听器自动发送，请勿直接回复。
                  </p>
                
                </body>
                </html>
                """.formatted(
                alertTime, dlqName, safeOrderNo, deathReason, retryCount,
                escapeHtml(messageBody), dlqName, safeOrderNo
        );
    }

    /** 对消息体中的 HTML 特殊字符转义，防止 XSS 破坏邮件结构。 */
    private String escapeHtml(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;");
    }
}
