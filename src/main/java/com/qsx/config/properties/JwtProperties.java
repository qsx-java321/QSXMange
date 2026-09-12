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

    /** access token 过期时间（毫秒） */
    private Long expiration;

    /** refresh token 过期时间（毫秒），每次续期重置（滑动续期） */
    private Long refreshExpiration;

    /** refresh token 绝对有效上限（毫秒），自首次登录起算 */
    private Long refreshMaxLifetime;

    /** 请求头名称 */
    private String header;

    /** Token 前缀 */
    private String prefix;
}