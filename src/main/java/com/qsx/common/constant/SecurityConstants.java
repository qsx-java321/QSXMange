package com.qsx.common.constant;

/**
 * 安全相关常量
 */
public final class SecurityConstants {

    private SecurityConstants() {
    }

    /** 请求头名称 */
    public static final String HEADER = "Authorization";

    /** Token 前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 登录接口 */
    public static final String LOGIN_URL = "/auth/login";

    /** 注册接口 */
    public static final String REGISTER_URL = "/auth/register";

    /** 刷新令牌接口（匿名放行） */
    public static final String REFRESH_URL = "/auth/refresh";
}