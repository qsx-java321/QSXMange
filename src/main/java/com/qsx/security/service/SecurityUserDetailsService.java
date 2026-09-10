package com.qsx.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.model.SecurityUser;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 加载用户信息给 Spring Security 认证使用
 */
@Service
public class SecurityUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public SecurityUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (user == null) {
            throw new UsernameNotFoundException("邮箱或密码错误");
        }
        // 加载该用户的角色码与权限码（每请求查库，权限变更即时生效）
        List<String> roleCodes = userMapper.selectRoleCodes(user.getId());
        List<String> permissionCodes = userMapper.selectPermissionCodes(user.getId());
        return new SecurityUser(user, roleCodes, permissionCodes);
    }
}