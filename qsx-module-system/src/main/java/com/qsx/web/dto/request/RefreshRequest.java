package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求。
 *
 * refresh token 自身即身份来源（服务端按 qsx:auth:rt:{refreshToken} 反查 userId），
 * 因此不再需要客户端自报 userId——客户端自报身份既多余又不可信。
 */
@Data
public class RefreshRequest {

    /**
     * 刷新令牌（32 字节随机数的 hex 编码）。
     *
     * 此处只校验非空，**不做形态（@Pattern）校验**：形态由 AuthSessionServiceImpl 判定并
     * 统一返回 1019。若在参数层用 @Pattern 拦成 400，会破坏「refresh 失败一律 1019」的
     * 前端契约——前端只在 1019 时清理登录态，拿到 400 会当作参数错误反复重试，用户卡在
     * 「令牌已失效但页面无反应」。服务端形态校验仍早于任何 Redis 访问，安全性质不变。
     */
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
