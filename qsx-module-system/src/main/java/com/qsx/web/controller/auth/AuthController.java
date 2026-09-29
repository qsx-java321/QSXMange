package com.qsx.web.controller.auth;

import com.qsx.common.result.Result;
import com.qsx.service.AuthService;
import com.qsx.web.dto.request.CaptchaSendRequest;
import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.ForgotPasswordRequest;
import com.qsx.web.dto.request.LoginRequest;
import com.qsx.web.dto.request.RefreshRequest;
import com.qsx.web.dto.request.RegisterRequest;
import com.qsx.web.vo.CaptchaVO;
import com.qsx.web.vo.LoginVO;
import com.qsx.web.vo.RefreshVO;
import com.qsx.web.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.success();
    }

    /**
     * 发送邮箱验证码（匿名放行；CHANGE_PASSWORD 场景要求登录态，由业务层判定）。
     * 正常模式下 data 为 null，仅调试模式返回 code。
     *
     * <p>客户端 IP 由这里取出后传给服务层（服务层不读 HTTP 上下文）：验证码是"往任意地址发信"
     * 的公开接口，只按邮箱限流等于没限——换邮箱即有新额度，必须补 IP 维度。
     *
     * <p>用 {@code getRemoteAddr()} 而不是自己解析 {@code X-Forwarded-For}：XFF 是客户端可伪造的，
     * 只有可信代理注入的才可信。若部署在反向代理后，应改用 Spring 的
     * {@code server.forward-headers-strategy=NATIVE}（由容器按代理链剔除可信跳数）。
     */
    @PostMapping("/captcha")
    public Result<CaptchaVO> captcha(@Valid @RequestBody CaptchaSendRequest request,
                                    HttpServletRequest httpRequest) {
        String code = authService.sendCaptcha(request, httpRequest.getRemoteAddr());
        return Result.success(code == null ? null : new CaptchaVO(code));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<RefreshVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.success(authService.refresh(request));
    }

    /**
     * 忘记密码（匿名重置，走邮箱验证码）。
     * 成功后该用户全部会话被销毁，且不自动登录——前端应引导用新密码重新登录。
     */
    @PostMapping("/forgot-password")
    public Result<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return Result.success();
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.success(authService.me());
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return Result.success();
    }
}