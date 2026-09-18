package com.qsx.security.service;

import com.qsx.security.cache.PermissionCacheService;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.port.AuthUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 加载用户信息给 Spring Security 认证使用
 *
 * 权限来源：PermissionCacheService（Redis 优先，未命中回源 MySQL 并回填）；
 * 用户行（含密码/禁用状态）仍实时查库，保证改密/禁用即时生效。
 *
 * 用户行经端口 {@link AuthUserRepository} 获取，由业务模块实现——查询语义
 * （含 @TableLogic 过滤已删除用户）完全由该实现决定。
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final AuthUserRepository authUserRepository;
    private final PermissionCacheService permissionCacheService;

    public SecurityUserDetailsService(AuthUserRepository authUserRepository,
                                      PermissionCacheService permissionCacheService) {
        this.authUserRepository = authUserRepository;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // 异常类型与文案必须保持不变：登录链路由 BadCredentialsException 转成业务码 1002
        AuthUserAccount account = authUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("邮箱或密码错误"));
        // 权限码从缓存读取（未命中回源 MySQL 并回填），getAuthorities() 只返回已填充集合
        PermissionCacheData data = permissionCacheService.load(account.id());
        return new SecurityUser(account, data.getRoles(), data.getPermissions());
    }

    /**
     * 按 userId 加载（认证过滤器用：access token 经 Redis 反查得到 userId）。
     *
     * 用户行仍实时查库（@TableLogic 自动过滤已删除用户），禁用/删除即时生效。
     * <b>必须与 loadUserByUsername 一样填充角色/权限</b>：若只传 account 不传
     * roles/permissions，authorities 会为空，导致所有 @PreAuthorize 变成 403。
     */
    public SecurityUser loadUserById(Long userId) {
        AuthUserAccount account = authUserRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        PermissionCacheData data = permissionCacheService.load(userId);
        return new SecurityUser(account, data.getRoles(), data.getPermissions());
    }
}