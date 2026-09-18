package com.qsx.common.constant;

/**
 * 内置角色编码常量（与 sql/init.sql 中 sys_role.code 对应）
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    /** 内置超管角色编码（禁止删除 / 不可被禁用 / 不可被强制登出） */
    public static final String ADMIN = "ADMIN";
}