package com.qsx.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 双 token 会话配置属性，对应 application.yml 中的 qsx.auth.session.*
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "qsx.auth.session")
public class AuthSessionProperties {

    /** access token 有效期（即 qsx:auth:at:* 的 TTL），默认 30 分钟 */
    @NotNull
    private Duration atTtl = Duration.ofMinutes(30);

    /** refresh token 与 session Hash 的有效期，每次刷新滑动续期，默认 7 天 */
    @NotNull
    private Duration rtTtl = Duration.ofDays(7);

    /** 自首次登录起的会话绝对上限，轮换不重置，默认 30 天 */
    @NotNull
    private Duration maxLifetime = Duration.ofDays(30);

    /**
     * 配置合法性校验（启动期失败，而非首次登录/刷新时才暴露）。
     *
     * 关键防呆：Duration 绑定允许纯数字，`max-lifetime: 30` 会被当作 **30 毫秒**——
     * 结果所有会话在首次刷新时被判超绝对上限而整批作废（用户被强制登出），
     * 且启动、登录、刷新接口都不会报任何配置错误。
     */
    @AssertTrue(message = "qsx.auth.session 配置不合法：at-ttl / rt-ttl / max-lifetime 均须 ≥ 1 分钟，"
            + "且满足 at-ttl ≤ rt-ttl ≤ max-lifetime（注意纯数字会被当作毫秒）")
    public boolean isTtlConfigurationValid() {
        if (atTtl == null || rtTtl == null || maxLifetime == null) {
            return true; // 交由 @NotNull 报告更准确的错误
        }
        Duration oneMinute = Duration.ofMinutes(1);
        return atTtl.compareTo(oneMinute) >= 0
                && rtTtl.compareTo(oneMinute) >= 0
                && maxLifetime.compareTo(oneMinute) >= 0
                && atTtl.compareTo(rtTtl) <= 0
                && rtTtl.compareTo(maxLifetime) <= 0;
    }

    /**
     * at 键 TTL（秒）。
     * 下限强制为 1：Duration 截断后为 0 时，SET ... EX 0 会让 Redis 报错（登录全挂），
     * 而 EXPIRE key 0 更会**直接删除 key**（静默毁掉会话），两种都必须挡住。
     */
    public long atTtlSeconds() {
        return Math.max(1, atTtl.toSeconds());
    }

    public long rtTtlSeconds() {
        return Math.max(1, rtTtl.toSeconds());
    }

    public long maxLifetimeMillis() {
        return Math.max(1, maxLifetime.toMillis());
    }
}
