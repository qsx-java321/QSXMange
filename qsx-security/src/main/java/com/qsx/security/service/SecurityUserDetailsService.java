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
 * Spring Security 用户加载实现（认证链路的取数入口）。
 *
 * <p>业务职责：供登录认证（按邮箱）与令牌过滤器（按 userId）加载用户详情，
 * 组装 {@link SecurityUser}（用户行 + 角色码/权限码 → authorities）。
 *
 * <p>使用场景：登录时由 Spring Security 的认证提供者按邮箱加载；
 * {@code TokenAuthenticationFilter} 在每次请求中按令牌反查出的 userId 加载。
 *
 * <p>核心依赖：{@link AuthUserRepository}（用户行端口，由业务模块实现）、
 * {@link PermissionCacheService}（角色/权限码，Redis 优先、未命中回源并回填）。
 *
 * <p>数据新鲜度约定：权限来源允许缓存（失效由权限变更事件驱动）；用户行
 * （含密码/禁用状态）仍实时查库，保证改密/禁用即时生效。用户行经端口获取——
 * 查询语义（含 @TableLogic 过滤已删除用户）完全由该实现决定。
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

    /**
     * 按邮箱加载用户详情（登录认证入口）。
     *
     * <p>查库未命中时抛 {@link UsernameNotFoundException}：异常类型与文案必须保持不变，
     * 登录链路由它转成「邮箱或密码错误」业务码；权限码经缓存读取（未命中回源并回填）。
     *
     * @param email 登录邮箱
     * @return 用户详情（含角色/权限 authorities，供认证与 @PreAuthorize 使用）
     * @throws UsernameNotFoundException 邮箱未注册（对外与密码错误同语义，防枚举）
     */
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
     * 按 userId 加载用户详情（认证过滤器用：access token 经 Redis 反查得到 userId）。
     *
     * <p>用户行仍实时查库（@TableLogic 自动过滤已删除用户），禁用/删除即时生效。
     * <b>必须与 loadUserByUsername 一样填充角色/权限</b>：若只传 account 不传
     * roles/permissions，authorities 会为空，导致所有 @PreAuthorize 变成 403。
     *
     * @param userId 用户 ID（由令牌反查得到）
     * @return 用户详情（含角色/权限 authorities）
     * @throws UsernameNotFoundException 用户不存在（已删除或从未存在）
     */
    public SecurityUser loadUserById(Long userId) {
        AuthUserAccount account = authUserRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
        PermissionCacheData data = permissionCacheService.load(userId);
        return new SecurityUser(account, data.getRoles(), data.getPermissions());
    }
}