package com.qsx.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 忘记密码（匿名重置）请求
 *
 * <p>两个密码字段的一致性校验**刻意不放在 DTO 的 @AssertTrue 上**，而是在 Service 层做：
 * DTO 校验失败会被统一压成 400，拿不到「两次输入的密码不一致」这个专用业务码（1030），
 * 前端就没法把「密码打错了」和「参数格式不对」分开提示。
 */
@Data
public class ForgotPasswordRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    private String captcha;

    /** 密码强度规则与注册保持一致（字母 + 数字，6-32 位） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在6-32位之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d\\S]{6,32}$",
            message = "密码需包含字母和数字")
    private String newPassword;

    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
}
