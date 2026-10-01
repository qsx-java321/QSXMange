package com.qsx.common.constant;

import java.util.Set;

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

    // ---- 预置菜单入口（与 init.sql 预置的 6 个 MENU 行一一对应）----
    // 命名刻意用 MENU_ENTRY_ 前缀，与上面的「菜单管理权限」（MENU_TREE / MENU_CREATE …）区分：
    // 这些**不是鉴权码**，不出现在任何 @PreAuthorize 里，只用于「系统内置行不可删」的保护集合
    public static final String MENU_ENTRY_SYSTEM = "system";
    public static final String MENU_ENTRY_USER = "system-user";
    public static final String MENU_ENTRY_ROLE = "system-role";
    public static final String MENU_ENTRY_PERM = "system-perm";
    public static final String MENU_ENTRY_MENU = "system-menu";
    public static final String MENU_ENTRY_LOG = "system-log";

    /** 菜单类型（对应 sys_permission.type） */
    public static final String TYPE_MENU = "MENU";

    /**
     * 系统内置 code 集合：init.sql 预置的 23 个权限码 + 6 个菜单入口码 = <b>29</b>。
     *
     * <p>用途唯一：{@code PermissionServiceImpl.delete} 的「内置行不可删」主闸。
     * 删除会**物理**清 {@code sys_role_permission} 关联（连坐 ADMIN）再逻辑删除权限行，
     * 而 {@code uk_perm_code} 是物理唯一索引、判重含已删行 ⇒ 同 code 无法经接口重建，只能改库。
     * 菜单被删虽不致接口 403，但会从所有人的菜单树消失且同样无法重建，属同一类失控，故一并纳入。
     *
     * <p>数据源必须与 {@code @PreAuthorize} 同源（本类常量），否则会出现「常量与注解失配」：
     * 注解里写着某个码、保护集合里却没有它（或反之）。
     */
    public static final Set<String> BUILT_IN_CODES = Set.of(
            USER_PAGE, USER_GET, USER_CREATE, USER_UPDATE, USER_DELETE,
            USER_ASSIGN_ROLE, USER_IMPORT, USER_EXPORT, USER_KICK,
            ROLE_PAGE, ROLE_GET, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE, ROLE_ASSIGN_PERM,
            PERM_PAGE, PERM_GET,
            MENU_TREE, MENU_CREATE, MENU_UPDATE, MENU_DELETE,
            LOG_PAGE, LOG_DELETE,
            MENU_ENTRY_SYSTEM, MENU_ENTRY_USER, MENU_ENTRY_ROLE, MENU_ENTRY_PERM,
            MENU_ENTRY_MENU, MENU_ENTRY_LOG);
}