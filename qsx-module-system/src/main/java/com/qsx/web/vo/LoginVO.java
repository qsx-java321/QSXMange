package com.qsx.web.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录结果视图对象
 */
@Data
public class LoginVO {

    private String token;
    private String refreshToken;
    private Long userId;
    private String email;
    private String nickname;

    /** 角色码 */
    private List<String> roles;

    /** 权限码 */
    private List<String> permissions;

    /**
     * 是否必须先修改密码。为 true 时，本次登录拿到的令牌只能访问 {@code /auth/**}，
     * 其它接口一律返回业务码 1037——前端应据此直接跳转改密页，不要等到业务请求失败再跳。
     */
    private Boolean mustChangePassword;
}