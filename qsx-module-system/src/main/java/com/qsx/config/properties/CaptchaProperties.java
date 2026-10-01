package com.qsx.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 邮箱验证码配置，对应 application.yml 中的 {@code qsx.captcha.*}
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "qsx.captcha")
public class CaptchaProperties {

    /**
     * 调试模式：true 时不投递邮件，验证码随接口响应直接返回。
     * <b>仅限本地联调</b>——此时任意调用者都能拿到验证码，校验形同虚设。
     */
    private boolean debug = false;

    /** 验证码有效期（Redis 中 code 键的 TTL） */
    @NotNull
    private Duration ttl = Duration.ofMinutes(5);

    /** 同一张验证码允许的最大失败次数，达到即作废 */
    @Min(value = 1, message = "max-attempts 至少为 1")
    @Max(value = 10, message = "max-attempts 不宜超过 10")
    private int maxAttempts = 5;

    /** 同场景同邮箱的发送间隔（60 秒内只允许发 1 次） */
    @NotNull
    private Duration sendInterval = Duration.ofSeconds(60);

    /** 同场景同邮箱的每日发送上限 */
    @Min(value = 1, message = "daily-limit 至少为 1")
    private int dailyLimit = 10;

    /**
     * 同一客户端 IP 的每日发送上限（跨场景、跨邮箱累计）。
     *
     * <p>邮箱维度的额度是"每个邮箱 10 次"，只按邮箱限流等于没限——攻击者换邮箱即有新额度，
     * 可用来向任意地址群发。IP 维度是唯一能约束"单个来源的总发送量"的口子。
     *
     * <p>注意取值要容忍 NAT 后的正常用户：太小会误伤同一出口 IP 的公司/学校网络。
     */
    @Min(value = 1, message = "ip-daily-limit 至少为 1")
    private int ipDailyLimit = 30;

    /**
     * 发件人地址。本地 Mailpit 可用任意地址；切换真实邮件服务商时，
     * 多数服务商要求发件地址已备案/校验，改这一项即可（代码零改动）。
     */
    @NotBlank
    private String fromAddress = "no-reply@qsx.local";

    /**
     * 启动期校验（对齐 {@code AuthSessionProperties} 的防呆风格）。
     *
     * <p>关键防呆：Duration 绑定允许纯数字，`ttl: 300` 会被当作 **300 毫秒**——
     * 结果验证码刚发出就过期，而客户端只会看到「验证码无效或已过期」，
     * 排查方向会被完全带偏。此处要求 ≥ 1 秒，并在提示里点明原因。
     */
    @AssertTrue(message = "qsx.captcha 配置不合法：ttl / send-interval 均须 ≥ 1 秒（注意纯数字会被当作毫秒）")
    public boolean isConfigurationValid() {
        if (ttl == null || sendInterval == null) {
            return true; // 交由 @NotNull 报告更准确的错误
        }
        Duration oneSecond = Duration.ofSeconds(1);
        return ttl.compareTo(oneSecond) >= 0 && sendInterval.compareTo(oneSecond) >= 0;
    }

    /** code / attempt 键 TTL（秒）。下限强制为 1：`EXPIRE key 0` 会直接删键 */
    public long ttlSeconds() {
        return Math.max(1, ttl.toSeconds());
    }

    public long sendIntervalSeconds() {
        return Math.max(1, sendInterval.toSeconds());
    }

    /** 日限键 TTL（秒）：日期串键，固定 24 小时即可，跨天后旧键自然过期 */
    public long dailyTtlSeconds() {
        return Duration.ofDays(1).toSeconds();
    }
}
