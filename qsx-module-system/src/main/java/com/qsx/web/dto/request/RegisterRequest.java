package com.qsx.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.qsx.common.constant.UserConstants;
import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    // 上限与「删除时改写邮箱的后缀」耦合，取值理由见 UserConstants.EMAIL_MAX；
    // 消息用常量拼接（编译期常量表达式），避免改了上限却忘了改文案
    @Size(max = UserConstants.EMAIL_MAX, message = "邮箱长度不能超过" + UserConstants.EMAIL_MAX)
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在6-32位之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d\\S]{6,32}$",
            message = "密码需包含字母和数字")
    private String password;

    /**
     * 邮箱验证码（REGISTER 场景）：先调 {@code POST /auth/captcha} 获取。
     * 注册是匿名接口，此字段是唯一能证明「邮箱可达」的凭据。
     */
    @NotBlank(message = "验证码不能为空")
    private String captcha;

    @Size(max = 50, message = "昵称长度不能超过50")
    private String nickname;
}