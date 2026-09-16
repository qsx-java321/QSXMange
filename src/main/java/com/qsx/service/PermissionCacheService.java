package com.qsx.service;

import com.qsx.security.model.PermissionCacheData;

import java.util.Collection;

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
     * 失效一批用户的权限缓存。
     * 受影响用户由调用方在改动关联表之前反查（见 UserRoleMapper），随事件携带至此
     */
    void evictUsers(Collection<Long> userIds);
}
