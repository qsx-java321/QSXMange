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
    EMAIL_FORMAT_ERROR(1008, "邮箱格式不正确"),

    // RBAC 角色/权限 (1009 起)
    ROLE_NOT_FOUND(1009, "角色不存在"),
    ROLE_CODE_EXISTS(1010, "角色编码已存在"),
    ROLE_IN_USE(1011, "角色已被用户使用，无法删除"),
    PERMISSION_NOT_FOUND(1012, "权限不存在"),

    // 菜单管理 (1013 起)
    MENU_NOT_FOUND(1013, "菜单不存在"),
    MENU_HAS_CHILDREN(1014, "存在子菜单，无法删除"),
    MENU_PARENT_INVALID(1015, "父菜单无效"),
    PERMISSION_CODE_EXISTS(1016, "菜单或权限标识已存在"),

    // Excel 导入导出 (1017 起)
    IMPORT_VALIDATE_FAILED(1017, "导入数据校验失败"),
    IMPORT_DATA_TOO_LARGE(1018, "导入数据量超过限制"),

    // 会话管理 (1019 起)
    REFRESH_TOKEN_INVALID(1019, "刷新令牌无效或已过期"),
    ADMIN_USER_CANNOT_DISABLE(1020, "内置超管用户不可禁用"),
    ADMIN_USER_CANNOT_KICK(1021, "内置超管用户不可强制登出"),
    CANNOT_OPERATE_SELF(1022, "不允许对自己执行该操作"),

    // 标识不可变 (1023 起)：code 是鉴权与菜单过滤的依据，改错会立刻锁死对应接口
    PERMISSION_CODE_IMMUTABLE(1023, "菜单或权限标识创建后不可修改，请新建并重新绑定"),
    ROLE_CODE_IMMUTABLE(1024, "角色编码创建后不可修改，请新建并重新绑定"),
    ADMIN_USER_CANNOT_DELETE(1025, "内置超管用户不可删除"),
    ADMIN_ROLE_CANNOT_DISABLE(1026, "内置超管角色不可停用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}