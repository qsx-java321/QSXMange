package com.qsx.mapper.adapter;

import com.qsx.mapper.UserMapper;
import com.qsx.security.port.UserAuthorityRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link UserAuthorityRepository} 的持久层适配器。
 *
 * <p>业务职责：提供用户角色码/权限码查询，供权限缓存回源（未命中或降级查库）使用。
 *
 * <p>核心依赖：{@link UserMapper}。
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

    /**
     * 查询用户的角色编码（授权判定用，过滤已停用角色）。
     *
     * @param userId 用户 ID
     * @return 角色编码列表；无角色时为空列表
     */
    @Override
    public List<String> selectRoleCodes(Long userId) {
        return userMapper.selectRoleCodes(userId);
    }

    /**
     * 查询用户的权限编码（经启用角色聚合）。
     *
     * @param userId 用户 ID
     * @return 权限编码列表；无权限时为空列表
     */
    @Override
    public List<String> selectPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodes(userId);
    }
}