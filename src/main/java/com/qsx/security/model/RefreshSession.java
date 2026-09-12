package com.qsx.security.model;

import lombok.Data;

/**
 * 刷新会话数据载体：作为 Redis value 以 JSON 序列化存储，
 * key 为 qsx:auth:refresh:{userId}，含刷新令牌的 SHA-256 哈希与首次登录时间。
 * 原始 refresh token 只下发给客户端，服务端仅存哈希，泄露 Redis 也无法伪造。
 */
@Data
public class RefreshSession {

    /** 刷新令牌的 SHA-256 哈希 */
    private String hash;

    /** 首次登录时间戳（毫秒），用于绝对有效上限（refresh-max-lifetime）判定 */
    private Long firstLoginTs;
}