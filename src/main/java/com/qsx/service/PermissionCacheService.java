package com.qsx.service;

import com.qsx.security.model.PermissionCacheData;

/**
 * RBAC 权限缓存服务：统一收口用户权限码（角色码 + 权限码）的读取与失效
 *
 * 读取：Redis 优先（key=qsx:auth:perm:{userId}），未命中回源 MySQL 并回填（含空集合，防穿透）；
 * Redis 异常降级为实时查库，不影响业务。
 * 失效：由 PermissionCacheEvictListener 在事务提交后（AFTER_COMMIT）调用，保证一致性。
 */
public interface PermissionCacheService {

    /**
     * 读取用户权限码（缓存优先，未命中回源并回填）
     */
    PermissionCacheData load(Long userId);

    /**
     * 失效单个用户的权限缓存（分配角色 / 删除用户时调用）
     */
    void evictUser(Long userId);

    /**
     * 失效某角色下全部用户的权限缓存（角色改码 / 删角色 / 分配权限时调用）
     */
    void evictUsersByRoleId(Long roleId);

    /**
     * 失效持有某权限的全部用户的权限缓存（删除菜单/权限时调用）
     */
    void evictUsersByPermissionId(Long permissionId);
}
