package com.qsx.service;

import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.LoginRequest;
import com.qsx.web.dto.request.RegisterRequest;
import com.qsx.web.vo.LoginVO;
import com.qsx.web.vo.UserVO;

/**
 * 认证服务
 */
public interface AuthService {

    /**
     * 注册
     */
    void register(RegisterRequest request);

    /**
     * 登录，返回 JWT 与用户信息
     */
    LoginVO login(LoginRequest request);

    /**
     * 获取当前登录用户信息
     */
    UserVO me();

    /**
     * 修改当前用户密码
     */
    void changePassword(ChangePasswordRequest request);
}