package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 刷新令牌请求：携带 userId 定位会话（refresh token 为随机串，服务端无法自行解析出用户）
 */
@Data
public class RefreshRequest {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}