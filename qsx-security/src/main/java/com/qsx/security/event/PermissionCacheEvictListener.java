package com.qsx.security.event;

import com.qsx.security.cache.PermissionCacheService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 权限缓存失效监听器：事务提交后（AFTER_COMMIT）执行失效，
 * 保证回源读取到的必然是新数据，避免竞态覆盖。
 *
 * 受影响用户由发布方预先反查并随事件携带，此处只做失效，不再回查数据库
 * （回查时机在提交之后，关联行可能已被同一事务删除）。
 */
@Component
public class PermissionCacheEvictListener {

    private final PermissionCacheService permissionCacheService;

    public PermissionCacheEvictListener(PermissionCacheService permissionCacheService) {
        this.permissionCacheService = permissionCacheService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvict(PermissionCacheEvictEvent event) {
        permissionCacheService.evictUsers(event.userIds());
    }
}
