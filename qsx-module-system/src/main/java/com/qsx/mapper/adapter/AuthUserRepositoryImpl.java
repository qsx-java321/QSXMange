package com.qsx.mapper.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.port.AuthUserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link AuthUserRepository} 的持久层适配器
 *
 * <p>只做「查库 + 类型转换」，不自己拼 SQL——@TableLogic 的逻辑删除过滤、
 * 邮箱唯一性的查询语义全部由 UserMapper 决定。
 */
@Component
public class AuthUserRepositoryImpl implements AuthUserRepository {

    private final UserMapper userMapper;

    public AuthUserRepositoryImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public Optional<AuthUserAccount> findByEmail(String email) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        return Optional.ofNullable(user).map(AuthUserRepositoryImpl::toAccount);
    }

    @Override
    public Optional<AuthUserAccount> findById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(AuthUserRepositoryImpl::toAccount);
    }

    private static AuthUserAccount toAccount(User user) {
        return new AuthUserAccount(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getPassword(),
                user.getStatus(),
                user.getCreateTime(),
                user.getUpdateTime(),
                // 库中为 TINYINT，历史行可能为 null（本列 NOT NULL DEFAULT 0，但手工 ALTER
                // 的库不保证），统一按 false 处理，避免拆箱 NPE
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }
}