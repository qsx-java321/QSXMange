package com.qsx.web.vo;

import lombok.Data;

/**
 * 登录结果视图对象
 */
@Data
public class LoginVO {

    private String token;
    private Long userId;
    private String email;
    private String nickname;
}