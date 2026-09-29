package com.qsx.security.port;

import java.time.LocalDateTime;

/**
 * 认证所需的用户行快照
 *
 * <p>用于把「认证内核不认识业务实体」这条边界固定下来：SecurityUser 原本直接持有
 * qsx-module-system 的 User 实体，导致 security → domain 的反向依赖。
 *
 * <p>字段是 User 实体中认证/展示相关的子集；createTime / updateTime 供 UserVO 使用。
 *
 * <p>由业务模块的 AuthUserRepository 实现负责从 User 实体转换，转换时<b>只做字段搬运</b>，
 * 查询语义（含 @TableLogic 逻辑删除过滤）完全由 UserMapper 决定。
 */
public record AuthUserAccount(
        Long id,
        String email,
        String nickname,
        String password,
        Integer status,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        boolean mustChangePassword) {

    /**
     * status: 0-正常，1-禁用
     *
     * <p>必须与 UserServiceImpl 的「非 0 即禁用」保持同一谓词——两处判定不一致会让
     * 「禁用用户仍能通过认证」或「正常用户被拒」二选一地发生。
     */
    public boolean enabled() {
        return status != null && status == 0;
    }

    /**
     * 是否仍在使用初始口令（true = 必须先改密）。
     *
     * <p>刻意**不**参与 {@link #enabled()} 的判定，也不进 {@code SecurityUser#isEnabled()}：
     * 登录链路上 {@code isEnabled()} 为假会被 {@code DaoAuthenticationProvider} 抛成
     * {@code DisabledException} → 1003（账号禁用），而"必须先改密"的账号**恰恰需要能登录**
     * ——不登录就没有会话，没有会话就改不了密码。判定放在认证过滤器里按请求执行，
     * 且对 {@code /auth/**} 整体豁免。
     */
    public boolean mustChangePassword() {
        return mustChangePassword;
    }
}