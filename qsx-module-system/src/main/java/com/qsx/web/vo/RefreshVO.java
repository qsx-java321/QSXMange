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

    /**
     * 是否必须先修改密码（与登录响应同一口径）。
     *
     * <p>刷新**不会**因该标志而失败——否则「AT 过期 → 刷新被拒 → 前端登出 → 重新登录」
     * 会让用户卡在原地拿不到可用令牌，反而改不了密。标志在这里回传只是为了让前端
     * 不必先撞一次 1037 才知道要跳改密页。轮换前的查库本来就取出了整行，零额外开销。
     */
    private Boolean mustChangePassword;
}