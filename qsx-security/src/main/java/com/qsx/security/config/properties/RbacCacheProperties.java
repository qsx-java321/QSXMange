package com.qsx.security.config.properties;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * RBAC 权限缓存配置属性，对应 application.yml 中的 qsx.rbac-cache.*
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "qsx.rbac-cache")
public class RbacCacheProperties {

    /** 总开关：false 时退化为实时查库（降级/特殊环境用） */
    private boolean enabled = true;

    /** 缓存兜底 TTL（主动失效优先，TTL 仅兜底），默认 30 分钟 */
    @NotNull
    private Duration ttl = Duration.ofMinutes(30);

    /**
     * 配置合法性校验（启动期失败，而不是上线后靠猜）。
     *
     * <p>关键防呆：Duration 绑定允许纯数字，`ttl: 30` 会被当作 **30 毫秒**——
     * 缓存永不命中、每个已登录请求多 2 条 SQL（查角色码 + 查权限码），
     * 而启动、登录、查询全部正常、**一条日志都不打**，属于"性能掉一个量级却无从察觉"的故障。
     *
     * <p>本类此前是三个配置类里唯一没有启动期校验的（另两个是 {@code AuthSessionProperties}
     * 与 {@code CaptchaProperties}）。
     * 想临时验证"关掉缓存"的行为请用 {@code enabled: false}，而不是把 TTL 调到极小。
     */
    @AssertTrue(message = "qsx.rbac-cache 配置不合法：ttl 须 ≥ 1 分钟"
            + "（注意纯数字会被当作毫秒；要验证无缓存行为请用 enabled=false）")
    public boolean isTtlConfigurationValid() {
        if (ttl == null) {
            return true; // 交由 @NotNull 报告更准确的错误
        }
        return ttl.compareTo(Duration.ofMinutes(1)) >= 0;
    }
}
