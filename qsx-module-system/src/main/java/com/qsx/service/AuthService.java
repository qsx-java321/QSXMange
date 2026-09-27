package com.qsx.service;

import com.qsx.web.dto.request.CaptchaSendRequest;
import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.ForgotPasswordRequest;
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
     * 发送邮箱验证码（场景前置条件在此判定：注册要求邮箱未注册、
     * 改密要求登录且邮箱为本人、忘记密码对未注册邮箱静默跳过以防枚举）。
     *
     * @return 验证码，**仅调试模式返回**（响应直返）；正常路径返回 null
     */
    String sendCaptcha(CaptchaSendRequest request);

    /**
     * 登录，返回 access token 与 refresh token（同一用户旧会话立即失效，单端登录）
     */
    LoginVO login(LoginRequest request);

    /**
     * 刷新令牌：access token 过期后，用 refresh token 轮换换取新的令牌对。
     * 旧 refresh token 一经使用立即失效（严格轮换，前端须保证刷新请求单飞）。
     */
    RefreshVO refresh(RefreshRequest request);

    /**
     * 忘记密码（匿名重置）：邮箱 + 验证码 + 新密码。
     *
     * <p>成功后**不自动登录**，且该用户的全部会话被销毁（客户端契约：重置成功即所有旧令牌失效）。
     *
     * @throws com.qsx.common.exception.BusinessException 1030 两次密码不一致；1028 验证码无效
     */
    void forgotPassword(ForgotPasswordRequest request);

    /**
     * 登出：清理当前用户的会话三键（qsx:auth:at/rt/session），access token 立即失效
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