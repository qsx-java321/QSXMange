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
    // 文案必须与实现一致：角色删除对普通角色是「级联解除用户/权限关联后逻辑删除」，
    // 1011 只在内置 ADMIN 角色上抛出。旧文案「角色已被用户使用，无法删除」会让调用方
    // 以为「被占用的角色删不掉」，据此写出错误的界面提示
    ROLE_IN_USE(1011, "内置超管角色不可删除"),
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
    ADMIN_ROLE_CANNOT_DISABLE(1026, "内置超管角色不可停用"),

    // 邮箱验证码 (1027 起)：通知类接口的语义是「验证码已发出」，与实名的用户/角色/菜单模块区分
    CAPTCHA_SEND_TOO_FREQUENT(1027, "验证码发送过于频繁，请稍后再试"),
    // 文案必须统一：不存在 / 已过期 / 填错一律同码同文案，避免客户端据此探测验证码状态
    CAPTCHA_INVALID(1028, "验证码无效或已过期"),
    CAPTCHA_ATTEMPT_EXCEEDED(1029, "验证码错误次数超限，请重新获取"),
    PASSWORD_NOT_MATCH(1030, "两次输入的密码不一致"),
    CHANGE_PASSWORD_CHANNEL_REQUIRED(1031, "修改密码需提供原密码或验证码之一"),
    CAPTCHA_EMAIL_MISMATCH(1032, "该场景验证码仅限本人邮箱"),

    // 内置资产不可破坏 (1033 起)：与 1011（内置角色不可删）/1026（内置角色不可停用）同一家族——
    // 判定只看「目标是不是内置资产」，与操作者身份解耦；一旦拒绝，库中状态必须原样不动
    ADMIN_USER_ROLE_IMMUTABLE(1033, "内置超管用户的角色不可修改"),
    ADMIN_GRANT_REQUIRES_ADMIN(1034, "授予内置超管角色需操作者本身为超管"),
    // 文案与 1016/1023 的「菜单或权限…」口径一致：本闸同时覆盖权限码行与预置菜单行
    SYSTEM_PERMISSION_CANNOT_DELETE(1035, "系统内置菜单或权限不可删除"),
    ADMIN_ROLE_PERMISSION_IMMUTABLE(1036, "内置超管角色的权限不可修改"),

    // 准入闸 (1037)：与上面两条家族不同——它不保护"资产"，而是限制"尚未完成首次改密的账号"。
    // 判定在 TokenAuthenticationFilter，对 /auth/** 整体豁免（否则用户没有会话就改不了密）。
    // HTTP 状态取 200 而非 403：它是业务状态而非权限缺失，与"业务失败一律 HTTP 200 + body 业务码"
    // 的主约定一致；403 继续只表示「已登录但无权限」。
    PASSWORD_CHANGE_REQUIRED(1037, "当前密码为初始密码，请先修改密码后再操作");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}