package com.qsx.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.qsx.common.constant.UserConstants;
import lombok.Data;

/**
 * 用户新增请求
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    // 上限与「删除时改写邮箱的后缀」耦合，取值理由见 UserConstants.EMAIL_MAX；
    // 消息用常量拼接（编译期常量表达式），避免改了上限却忘了改文案
    @Size(max = UserConstants.EMAIL_MAX, message = "邮箱长度不能超过" + UserConstants.EMAIL_MAX)
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在6-32位之间")
    private String password;

    @Size(max = 50, message = "昵称长度不能超过50")
    private String nickname;

    /**
     * 状态：0-正常，1-禁用，默认正常。
     * 必须限定取值：系统以「status != 0 即禁用」判定（SecurityUser.isEnabled），
     * 放任其它值会造成「账号被禁用但按未禁用处理」两侧语义错位
     */
    @Min(value = 0, message = "状态只能为 0(正常) 或 1(禁用)")
    @Max(value = 1, message = "状态只能为 0(正常) 或 1(禁用)")
    private Integer status;
}