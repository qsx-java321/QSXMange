package com.qsx.web.dto.request;

import com.qsx.security.session.AuthSessionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
     * 形态校验先行：令牌会直接参与 Redis 键名构造，挡掉畸形输入可避免无谓的 Redis 往返
     */
    @NotBlank(message = "刷新令牌不能为空")
    @Pattern(regexp = AuthSessionService.TOKEN_REGEX, message = "刷新令牌格式不正确")
    private String refreshToken;
}
