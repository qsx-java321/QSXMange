package com.qsx.web.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发送验证码响应
 *
 * <p>正常模式下整个 data 为 null（只提示「验证码已发送」，不返回任何可用信息）；
 * 仅 {@code qsx.captcha.debug=true} 时返回 code 供本地联调。
 */
@Data
@AllArgsConstructor
public class CaptchaVO {

    /** 验证码：仅调试模式非空 */
    private String code;
}
