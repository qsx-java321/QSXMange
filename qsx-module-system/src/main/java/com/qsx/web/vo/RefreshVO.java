package com.qsx.web.vo;

import lombok.Data;

/**
 * 刷新令牌结果视图：access token 过期后，用 refresh token 换取的新令牌对
 */
@Data
public class RefreshVO {

    /** 新的 access token（32 字节随机串，有效性以 Redis 为准） */
    private String token;

    /** 新的 refresh token（旧令牌轮换后立即失效） */
    private String refreshToken;
}