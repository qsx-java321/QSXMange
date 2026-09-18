package com.qsx.security.session;

/**
 * 双 token 有状态会话服务：Redis 是令牌有效性的唯一真相源。
 *
 * 三键模型（key 构造见 {@link AuthRedisKeys}）：
 * <pre>
 * qsx:auth:at:{accessToken}   -> userId   TTL 30 分钟    删除即吊销
 * qsx:auth:rt:{refreshToken}  -> userId   TTL 7 天       轮换时删旧立新
 * qsx:auth:session:{userId}   -> Hash{accessToken, refreshToken, firstLoginTs}
 *                                          TTL 7 天滑动   单端覆盖 / 按用户清理 / 绝对上限
 * </pre>
 *
 * **fail-closed**：本服务所有 Redis 异常一律向上抛出，绝不降级放行——
 * access token 是随机串，Redis 之外无法确定身份，宁可拒绝认证也不能错误放行。
 * 这与权限缓存（fail-open：Redis 异常降级为查库）策略相反，两者边界不可混淆。
 */
public interface AuthSessionService {

    /**
     * 令牌形态：32 字节 SecureRandom 的 hex 编码（64 位小写十六进制）。
     * 客户端提交的令牌会直接参与 Redis 键名构造，先做形态校验可挡掉畸形输入与无谓往返。
     */
    String TOKEN_REGEX = "^[0-9a-f]{64}$";

    /**
     * 登录签发：写入一对新令牌并建立会话索引；同一用户旧会话的 at/rt 立即失效（单端登录）。
     *
     * @throws IllegalStateException Redis 异常（fail-closed，调用方按系统错误处理）
     */
    AuthSession issue(Long userId);

    /**
     * 刷新轮换：旧 refresh token 一经使用立即失效，返回新的令牌对。
     * 会话超过绝对上限时清除整个会话。
     *
     * @throws com.qsx.common.exception.BusinessException 1019（令牌无效 / 已过期 / 超绝对上限 / Redis 异常）
     */
    AuthSession rotate(String refreshToken);

    /**
     * 清理指定用户的会话（登出 / 踢人 / 禁用 / 删除 / 改密）。幂等。
     *
     * @throws IllegalStateException Redis 异常（fail-closed，由调用方决定是否阻断）
     */
    void remove(Long userId);

    /**
     * access token -> userId；令牌无效返回 null。
     *
     * @throws org.springframework.dao.DataAccessException Redis 不可达（**不吞异常**：
     *         调用方必须能区分「令牌不存在」与「Redis 故障」，否则线上故障与令牌集体过期无法分辨）
     */
    Long findUserIdByAccessToken(String accessToken);

    /**
     * refresh token -> userId；令牌无效返回 null。异常语义同 {@link #findUserIdByAccessToken}
     */
    Long findUserIdByRefreshToken(String refreshToken);
}
