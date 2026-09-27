package com.qsx.common.constant;

/**
 * 邮箱验证码场景
 *
 * <p>scene 是验证码 Redis 键的一个维度（{@code qsx:auth:cap:{scene}:{email}}），
 * 校验时**必须显式传入**：从结构上隔离三种验证码，「注册码」不可能通过「重置密码」的校验。
 */
public enum CaptchaScene {

    /** 注册：要求邮箱未注册 */
    REGISTER("注册"),

    /** 忘记密码（匿名重置通道）：邮箱是否注册一律返回一致响应，防枚举 */
    FORGOT_PASSWORD("重置密码"),

    /** 修改密码通道 B：要求已登录，且邮箱必须是本人 */
    CHANGE_PASSWORD("修改密码");

    private final String label;

    CaptchaScene(String label) {
        this.label = label;
    }

    /** 中文场景名（邮件主题用） */
    public String label() {
        return label;
    }
}
