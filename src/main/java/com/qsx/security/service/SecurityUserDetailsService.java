package com.qsx.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.model.SecurityUser;
import com.qsx.service.PermissionCacheService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 加载用户信息给 Spring Security 认证使用
 *
 * 权限来源：PermissionCacheService（Redis 优先，未命中回源 MySQL 并回填）；
 * 用户行（含密码/禁用状态）仍实时查库，保证改密/禁用即时生效。
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;
    private final PermissionCacheService permissionCacheService;

    public SecurityUserDetailsService(UserMapper userMapper,
                                      PermissionCacheService permissionCacheService) {
        this.userMapper = userMapper;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (user == null) {
            throw new UsernameNotFoundException("邮箱或密码错误");
        }
        // 权限码从缓存读取（未命中回源 MySQL 并回填），getAuthorities() 只返回已填充集合
        PermissionCacheData data = permissionCacheService.load(user.getId());
        return new SecurityUser(user, data.getRoles(), data.getPermissions());
    }

    /**
     * 按 userId 加载（认证过滤器用：access token 经 Redis 反查得到 userId）。
     *
     * 用户行仍实时查库（@TableLogic 自动过滤已删除用户），禁用/删除即时生效。
     * <b>必须与 loadUserByUsername 一样填充角色/权限</b>：若改用单参构造
     * {@code new SecurityUser(user)}，authorities 会为空，导致所有 @PreAuthorize 变成 403。
     */
    public SecurityUser loadUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        PermissionCacheData data = permissionCacheService.load(userId);
        return new SecurityUser(user, data.getRoles(), data.getPermissions());
    }
}