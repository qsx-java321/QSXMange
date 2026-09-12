package com.qsx.config.event;

import com.qsx.service.PermissionCacheService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 权限缓存失效监听器：事务提交后（AFTER_COMMIT）执行失效，
 * 保证回源读取到的必然是新数据，避免竞态覆盖
 */
@Component
public class PermissionCacheEvictListener {

    private final PermissionCacheService permissionCacheService;

    public PermissionCacheEvictListener(PermissionCacheService permissionCacheService) {
        this.permissionCacheService = permissionCacheService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvict(PermissionCacheEvictEvent event) {
        switch (event.getType()) {
            case USER -> permissionCacheService.evictUser(event.getId());
            case ROLE -> permissionCacheService.evictUsersByRoleId(event.getId());
            case PERMISSION -> permissionCacheService.evictUsersByPermissionId(event.getId());
        }
    }
}
