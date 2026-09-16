package com.qsx.security.session;

/**
 * 会话相关 Redis key 构造器：前缀常量的唯一来源。
 *
 * 三键模型（详见 AuthSessionServiceImpl 类注释）：
 * <pre>
 * qsx:auth:at:{accessToken}      -> userId      TTL 30 分钟
 * qsx:auth:rt:{refreshToken}     -> userId      TTL 7 天
 * qsx:auth:session:{userId}      -> Hash{accessToken, refreshToken, firstLoginTs}   TTL 7 天（滑动）
 * </pre>
 *
 * 前缀同时以 ARGV 形式传给 Lua 脚本：脚本内需要按「只有进脚本后才知道」的令牌值
 * 反查并删除旧键，因此前缀必须由 Java 侧单一来源提供，不能在脚本里写死。
 */
public final class AuthRedisKeys {

    /** access token -> userId */
    public static final String AT_PREFIX = "qsx:auth:at:";

    /** refresh token -> userId */
    public static final String RT_PREFIX = "qsx:auth:rt:";

    /** userId -> 会话索引 Hash */
    public static final String SESSION_PREFIX = "qsx:auth:session:";

    private AuthRedisKeys() {
    }

    public static String at(String accessToken) {
        return AT_PREFIX + accessToken;
    }

    public static String rt(String refreshToken) {
        return RT_PREFIX + refreshToken;
    }

    public static String session(Long userId) {
        return SESSION_PREFIX + userId;
    }
}
