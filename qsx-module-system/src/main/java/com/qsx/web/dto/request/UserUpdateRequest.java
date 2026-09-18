package com.qsx.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户修改请求
 */
@Data
public class UserUpdateRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Size(max = 50, message = "昵称长度不能超过50")
    private String nickname;

    /**
     * 状态：0-正常，1-禁用。
     * 必须限定取值：系统以「status != 0 即禁用」判定（SecurityUser.isEnabled），
     * 而禁用保护与「禁用即踢下线」此前按 status==1 判定，两个谓词不一致时，
     * 传入非 0/1 的其它值会绕过保护规则（可禁用超管/自己）且不清理会话
     */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能为 0(正常) 或 1(禁用)")
    @Max(value = 1, message = "状态只能为 0(正常) 或 1(禁用)")
    private Integer status;
}