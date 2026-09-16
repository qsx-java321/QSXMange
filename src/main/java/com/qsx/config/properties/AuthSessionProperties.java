package com.qsx.config.properties;

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
