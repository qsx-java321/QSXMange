package com.qsx.security.constant;

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

    /**
     * 发送邮箱验证码接口（匿名放行）。
     *
     * <p>URL 层放行 ≠ 无需登录：CHANGE_PASSWORD 场景要求登录态，由业务层
     * {@code AuthServiceImpl.sendCaptcha} 调 {@code SecurityUtils.getCurrentUser()} 强制校验
     * （permitAll 只跳过授权，认证过滤器照常执行并填充 SecurityContext）。
     */
    public static final String CAPTCHA_URL = "/auth/captcha";

    /** 忘记密码重置接口（匿名放行）：凭「邮箱 + 验证码」重置密码，无需登录态 */
    public static final String FORGOT_PASSWORD_URL = "/auth/forgot-password";

    /**
     * 请求属性：认证链路因**基础设施异常**（如 Redis 不可达）而判为未认证。
     * 此类 401 不写操作日志——故障期间 100% 请求都会 401，
     * 若逐条落库会形成写库风暴，把 Redis 的局部故障放大成全站不可用。
     */
    public static final String AUTH_INFRA_ERROR_ATTR = "qsx.auth.infraError";
}