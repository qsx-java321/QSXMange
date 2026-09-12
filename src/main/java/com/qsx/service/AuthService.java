package com.qsx.service;

import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.LoginRequest;
import com.qsx.web.dto.request.RefreshRequest;
import com.qsx.web.dto.request.RegisterRequest;
import com.qsx.web.vo.LoginVO;
import com.qsx.web.vo.RefreshVO;
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
     * 登录，返回 access token 与 refresh token
     */
    LoginVO login(LoginRequest request);

    /**
     * 刷新令牌：access token 过期后，用 refresh token 轮换换取新的令牌对
     */
    RefreshVO refresh(RefreshRequest request);

    /**
     * 登出：删除当前用户的刷新会话
     */
    void logout();

    /**
     * 获取当前登录用户信息
     */
    UserVO me();

    /**
     * 修改当前用户密码
     */
    void changePassword(ChangePasswordRequest request);
}