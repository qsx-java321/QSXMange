package com.qsx.security.session;

/**
 * 一次签发的会话凭证（明文令牌）。
 *
 * **仅在服务端内部传递**，绝不落日志：toString 已对令牌脱敏，
 * 防止有人无意间 log.info("{}", session) 把可用令牌写进日志文件
 * （本项目 logging.level.com.qsx 已开到 debug）。
 */
public record AuthSession(Long userId, String accessToken, String refreshToken) {

    private static final int VISIBLE_PREFIX = 8;

    @Override
    public String toString() {
        return "AuthSession{userId=" + userId
                + ", accessToken=" + mask(accessToken)
                + ", refreshToken=" + mask(refreshToken) + '}';
    }

    /**
     * 只暴露前 8 个字符用于排查定位：令牌有 256 位熵，泄露 32 位后仍不可枚举
     */
    private static String mask(String token) {
        if (token == null || token.length() <= VISIBLE_PREFIX) {
            return "***";
        }
        return token.substring(0, VISIBLE_PREFIX) + "***(masked)";
    }
}
