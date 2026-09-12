package com.qsx.common.constant;

/**
 * 权限码常量（与 sql/init.sql 中 sys_permission.code 一一对应）
 *
 * 用于 @PreAuthorize("hasAuthority('" + PermissionConstants.XXX + "')")。
 * 注意：新增权限码时需同步在 init.sql 追加，并为 ADMIN 角色补绑 sys_role_permission，
 * 否则超级管理员也拿不到新权限。
 */
public final class PermissionConstants {

    private PermissionConstants() {
    }

    // ---- 用户管理 ----
    public static final String USER_PAGE = "user:page";
    public static final String USER_GET = "user:get";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_ASSIGN_ROLE = "user:assign-role";
    public static final String USER_IMPORT = "user:import";
    public static final String USER_EXPORT = "user:export";
    public static final String USER_KICK = "user:kick";

    // ---- 角色管理 ----
    public static final String ROLE_PAGE = "role:page";
    public static final String ROLE_GET = "role:get";
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";
    public static final String ROLE_ASSIGN_PERM = "role:assign";

    // ---- 权限管理 ----
    public static final String PERM_PAGE = "perm:page";
    public static final String PERM_GET = "perm:get";

    // ---- 菜单管理 ----
    public static final String MENU_TREE = "menu:tree";
    public static final String MENU_CREATE = "menu:create";
    public static final String MENU_UPDATE = "menu:update";
    public static final String MENU_DELETE = "menu:delete";

    // ---- 日志管理 ----
    public static final String LOG_PAGE = "log:page";
    public static final String LOG_DELETE = "log:delete";

    /** 菜单类型（对应 sys_permission.type） */
    public static final String TYPE_MENU = "MENU";
    /** 按钮权限类型（对应 sys_permission.type） */
    public static final String TYPE_PERMISSION = "PERMISSION";
}