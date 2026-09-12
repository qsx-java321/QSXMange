package com.qsx.config.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 权限缓存失效事件：在写操作事务内发布，由 PermissionCacheEvictListener
 * 于事务提交后（AFTER_COMMIT）执行对应失效，避免「提交前删缓存被旧数据回填覆盖」的竞态
 */
@Data
@AllArgsConstructor
public class PermissionCacheEvictEvent {

    public enum Type {
        /** 单个用户（分配角色 / 删除用户） */
        USER,
        /** 某角色下全部用户（角色改码 / 删角色 / 分配权限） */
        ROLE,
        /** 持有某权限的全部用户（删除菜单/权限） */
        PERMISSION
    }

    private final Type type;

    /** 对应 type 的 id：userId / roleId / permissionId */
    private final Long id;
}
