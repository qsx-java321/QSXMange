package com.qsx.security.port;

import java.util.Optional;

/**
 * 按邮箱 / ID 读取认证所需用户行的端口
 *
 * <p>认证链路（登录、每请求令牌反查）需要实时查库，否则禁用与删除无法即时生效。
 * security 模块通过本端口取数，由业务模块提供实现，避免 security 直接依赖 UserMapper。
 *
 * <p>实现约定：
 * <ul>
 *   <li>返回 {@code Optional.empty()} 表示用户不存在（可能含被逻辑删除的用户），
 *       由调用方决定如何抛出异常；</li>
 *   <li>不得自行拼 SQL，直接委托 UserMapper，以保留 @TableLogic 的过滤语义。</li>
 * </ul>
 */
public interface AuthUserRepository {

    Optional<AuthUserAccount> findByEmail(String email);

    Optional<AuthUserAccount> findById(Long userId);
}