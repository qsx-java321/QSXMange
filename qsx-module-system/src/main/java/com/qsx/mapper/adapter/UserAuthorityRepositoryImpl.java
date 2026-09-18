package com.qsx.mapper.adapter;

import com.qsx.mapper.UserMapper;
import com.qsx.security.port.UserAuthorityRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link UserAuthorityRepository} 的持久层适配器
 *
 * <p>原样透传 UserMapper 的查询，尤其不得改动 selectRoleCodes 的
 * {@code r.status = 0} 过滤条件（停用角色即时回收权限依赖它）。
 */
@Component
public class UserAuthorityRepositoryImpl implements UserAuthorityRepository {

    private final UserMapper userMapper;

    public UserAuthorityRepositoryImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public List<String> selectRoleCodes(Long userId) {
        return userMapper.selectRoleCodes(userId);
    }

    @Override
    public List<String> selectPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodes(userId);
    }
}