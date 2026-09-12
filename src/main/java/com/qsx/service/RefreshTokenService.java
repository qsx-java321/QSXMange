package com.qsx.service;

/**
 * 刷新会话（refresh token）服务：单端登录模型，
 * Redis key = qsx:auth:refresh:{userId}，TTL 滑动续期，30 天绝对上限。
 */
public interface RefreshTokenService {

    /**
     * 为指定用户签发一个新的刷新令牌并写入 Redis（覆盖旧会话，实现单端登录）
     *
     * @return 原始刷新令牌（仅此一次下发）
     */
    String issue(Long userId);

    /**
     * 校验并轮换刷新令牌：校验 hash 匹配 + 绝对有效上限，
     * 通过后删除旧值、写入新值（保留首次登录时间），旧令牌立即失效
     *
     * @return 新的原始刷新令牌
     * @throws com.qsx.common.exception.BusinessException 无效/过期/绝对上限到期（1019）
     */
    String rotate(Long userId, String rawToken);

    /**
     * 删除指定用户的刷新会话（幂等）。Redis 异常时抛出，由调用方决定降级策略
     */
    void remove(Long userId);
}