package com.chuhezhe.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DLQ 邮件告警配置属性（US-023）。
 *
 * <p>对应 application.yml 中的 {@code dlq.alert.*} 前缀：
 * <pre>
 * dlq:
 *   alert:
 *     enabled: true
 *     from: alert@example.com
 *     to:
 *       - oncall@example.com
 *     subject-prefix: "[告警][product-service] 死信队列有消息"
 *     cooldown-seconds: 300
 * </pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code enabled}：告警开关，测试/本地环境设 false 可屏蔽告警避免误扰。</li>
 *   <li>{@code to}：支持多收件人，方便同时通知值班人与研发负责人。</li>
 *   <li>{@code cooldownSeconds}：同一 orderNo 的冷却窗口（秒），防止同一条毒消息反复写入 DLQ
 *       时刷屏告警；超出窗口再次告警以保证不遗漏。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "dlq.alert")
public class DlqAlertProperties {

    /** 告警功能总开关。false 时 {@link com.chuhezhe.product.service.DlqAlertEmailService} 直接跳过发送。 */
    private boolean enabled = true;

    /** 发件人地址，须与 spring.mail.username 保持一致。 */
    private String from;

    /** 收件人列表，至少配置一个。 */
    private List<String> to = new ArrayList<>();

    /** 邮件标题前缀，便于邮件客户端过滤规则归类。 */
    private String subjectPrefix = "[告警] 死信队列有消息";

    /**
     * 同一 orderNo 的告警冷却时间（秒）。
     * <p>在冷却窗口内重复进入 DLQ 的同一 orderNo 不重复发邮件，窗口到期后恢复告警。
     * 默认 300 秒（5 分钟）。
     */
    private long cooldownSeconds = 300;

    // -------  getters / setters  -------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public List<String> getTo() {
        return to;
    }

    public void setTo(List<String> to) {
        this.to = to;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }
}
