package com.qsx.security.event;

import java.util.Collection;
import java.util.Set;

/**
 * 权限缓存失效事件：在写操作事务内发布，由 PermissionCacheEvictListener
 * 于事务提交后（AFTER_COMMIT）执行失效，避免「提交前删缓存被旧数据回填覆盖」的竞态
 *
 * 载荷为发布方**在改动关联表之前**反查好的受影响用户集合，而不是角色/权限 id：
 * 删除角色/权限会清空 sys_user_role / sys_role_permission，
 * 若延到 AFTER_COMMIT 再反查必然得到空集，导致缓存中的旧权限一直生效到 TTL 到期。
 */
public record PermissionCacheEvictEvent(Set<Long> userIds) {

    public PermissionCacheEvictEvent {
        // 不可变快照，防止发布后载荷被调用方继续修改
        userIds = Set.copyOf(userIds);
    }

    /** 单个用户（分配角色 / 删除用户） */
    public static PermissionCacheEvictEvent ofUser(Long userId) {
        return new PermissionCacheEvictEvent(Set.of(userId));
    }

    /** 一批用户（角色/权限变更，由调用方先行反查） */
    public static PermissionCacheEvictEvent ofUsers(Collection<Long> userIds) {
        return new PermissionCacheEvictEvent(Set.copyOf(userIds));
    }
}
