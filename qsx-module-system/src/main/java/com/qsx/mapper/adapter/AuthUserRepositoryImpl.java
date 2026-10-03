package com.qsx.mapper.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.port.AuthUserRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link AuthUserRepository} 的持久层适配器。
 *
 * <p>业务职责：把用户行查询结果转换为认证内核使用的 {@link AuthUserAccount}，
 * 供登录认证与令牌过滤器加载用户详情。
 *
 * <p>核心依赖：{@link UserMapper}。
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

    /**
     * 按邮箱查询用户账户（登录认证用）；查询语义由 UserMapper 决定（含逻辑删除过滤、
     * 库 collation 决定的大小写敏感性）。
     *
     * @param email 登录邮箱
     * @return 用户账户；不存在时为 {@link Optional#empty()}
     */
    @Override
    public Optional<AuthUserAccount> findByEmail(String email) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        return Optional.ofNullable(user).map(AuthUserRepositoryImpl::toAccount);
    }

    /**
     * 按 ID 查询用户账户（令牌过滤器每请求加载用）；查询语义由 UserMapper 决定。
     *
     * @param userId 用户 ID
     * @return 用户账户；不存在或已逻辑删除时为 {@link Optional#empty()}
     */
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