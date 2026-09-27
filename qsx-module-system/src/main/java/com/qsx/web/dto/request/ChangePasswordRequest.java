package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求（双通道二选一）
 *
 * <table>
 *   <tr><th>通道</th><th>字段</th><th>校验</th></tr>
 *   <tr><td>A（原有）</td><td>{@code oldPassword}</td><td>BCrypt 匹配当前密码，失败 1006</td></tr>
 *   <tr><td>B（新增）</td><td>{@code captcha}</td><td>CHANGE_PASSWORD 场景验证码，码只能发到本人邮箱</td></tr>
 * </table>
 *
 * <p>{@code oldPassword} 刻意**不加 @NotBlank**：双通道下它可以是空的。
 * 二选一校验放在 Service 层，因为 DTO 上的 @AssertTrue 失败会被统一压成 400，
 * 拿不到「必须提供旧密码或验证码之一」这个专用业务码（1031）。
 */
@Data
public class ChangePasswordRequest {

    /** 通道 A：原密码。与 captcha 二选一（都传 / 都不传都会被拒，1031） */
    private String oldPassword;

    /** 通道 B：邮箱验证码（需先登录，并向本人邮箱发码） */
    private String captcha;

    /**
     * 新密码。强度规则与注册保持一致——
     * 此前改密只校验长度，比注册还松，等于给了一条「用弱密码覆盖强密码」的路径。
     */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在6-32位之间")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d\\S]{6,32}$",
            message = "密码需包含字母和数字")
    private String newPassword;
}
