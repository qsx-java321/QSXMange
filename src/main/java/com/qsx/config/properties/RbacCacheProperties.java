package com.qsx.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RBAC 权限缓存配置属性，对应 application.yml 中的 qsx.rbac-cache.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "qsx.rbac-cache")
public class RbacCacheProperties {

    /** 总开关：false 时退化为实时查库（降级/特殊环境用） */
    private boolean enabled = true;

    /** 缓存兜底 TTL（主动失效优先，TTL 仅兜底），默认 30 分钟 */
    private Duration ttl = Duration.ofMinutes(30);
}
