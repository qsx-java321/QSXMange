package com.qsx.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性，对应 application.yml 中的 jwt.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 密钥 */
    private String secret;

    /** 过期时间（毫秒） */
    private Long expiration;

    /** 请求头名称 */
    private String header;

    /** Token 前缀 */
    private String prefix;
}