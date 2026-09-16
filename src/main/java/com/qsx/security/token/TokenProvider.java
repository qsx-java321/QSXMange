package com.qsx.security.token;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 令牌生成器：access token 与 refresh token **同构**——
 * 32 字节 SecureRandom 的 hex 编码（64 字符），无签名、无载荷、不可解析出任何用户信息。
 *
 * 身份完全由 Redis 侧映射（qsx:auth:at:* / qsx:auth:rt:*）决定，
 * 因此改邮箱、改昵称等用户数据变更不会让已签发令牌失配。
 */
@Component
public class TokenProvider {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成 access token
     */
    public String generateAccessToken() {
        return secureRandomHex();
    }

    /**
     * 生成 refresh token
     */
    public String generateRefreshToken() {
        return secureRandomHex();
    }

    private String secureRandomHex() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
