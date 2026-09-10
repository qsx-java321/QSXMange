package com.qsx.common.result;

import lombok.Getter;

/**
 * 统一响应码
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),

    // 参数校验 (400)
    BAD_REQUEST(400, "请求参数错误"),
    VALIDATE_FAILED(400, "参数校验失败"),

    // 认证授权 (401/403)
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),

    // 业务异常 (1000 起)
    EMAIL_ALREADY_REGISTERED(1001, "该邮箱已被注册"),
    EMAIL_OR_PASSWORD_ERROR(1002, "邮箱或密码错误"),
    USER_DISABLED(1003, "账号已被禁用"),
    USER_NOT_FOUND(1004, "用户不存在"),
    USER_ALREADY_EXISTS(1005, "用户已存在"),
    OLD_PASSWORD_ERROR(1006, "原密码错误"),
    NOT_FOUND(1007, "数据不存在"),
    EMAIL_FORMAT_ERROR(1008, "邮箱格式不正确");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}