package com.qsx.web.dto.request;

import com.qsx.common.constant.CaptchaScene;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发送验证码请求
 *
 * <p>scene 用枚举承载：取值非法时 Jackson 绑定失败 →
 * {@code HttpMessageNotReadableException} → 全局异常处理返回 400，
 * 不必在 DTO 上再写一份与枚举重复的正则。
 */
@Data
public class CaptchaSendRequest {

    /** 场景：REGISTER / FORGOT_PASSWORD / CHANGE_PASSWORD */
    @NotNull(message = "场景不能为空")
    private CaptchaScene scene;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
