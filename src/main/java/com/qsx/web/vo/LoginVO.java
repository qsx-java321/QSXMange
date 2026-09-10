package com.qsx.web.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录结果视图对象
 */
@Data
public class LoginVO {

    private String token;
    private Long userId;
    private String email;
    private String nickname;

    /** 角色码 */
    private List<String> roles;

    /** 权限码 */
    private List<String> permissions;
}