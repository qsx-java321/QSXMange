package com.qsx.service.impl;

import com.qsx.common.constant.CaptchaScene;
import com.qsx.common.constant.UserConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.session.AuthSession;
import com.qsx.security.session.AuthSessionService;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.AuthService;
import com.qsx.security.cache.PermissionCacheService;
import com.qsx.service.CaptchaService;
import com.qsx.service.UserService;
import com.qsx.service.captcha.CaptchaRedisKeys;
import com.qsx.web.dto.request.CaptchaSendRequest;
import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.ForgotPasswordRequest;
import com.qsx.web.dto.request.LoginRequest;
import com.qsx.web.dto.request.RefreshRequest;
import com.qsx.web.dto.request.RegisterRequest;
import com.qsx.web.vo.LoginVO;
import com.qsx.web.vo.RefreshVO;
import com.qsx.web.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务实现
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final AuthSessionService authSessionService;
    private final PermissionCacheService permissionCacheService;
    private final CaptchaService captchaService;

    public AuthServiceImpl(UserService userService,
                           UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           AuthSessionService authSessionService,
                           PermissionCacheService permissionCacheService,
                           CaptchaService captchaService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.authSessionService = authSessionService;
        this.permissionCacheService = permissionCacheService;
        this.captchaService = captchaService;
    }

    @Override
    public String sendCaptcha(CaptchaSendRequest request, String clientIp) {
        CaptchaScene scene = request.getScene();
        String email = CaptchaRedisKeys.normalizeEmail(request.getEmail());

        switch (scene) {
            case REGISTER -> {
                // 注册要求邮箱未被占用：直接复用注册接口的判定与文案（1001）
                if (userService.getByEmail(email) != null) {
                    throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
                }
            }
            case CHANGE_PASSWORD -> {
                // 该场景的码只能发给本人：未登录时 SecurityUtils 抛 401（
                // /auth/captcha 在 URL 层 permitAll，但认证过滤器照常执行，
                // 带合法令牌时 SecurityContext 里就有身份）
                AuthUserAccount current = SecurityUtils.getCurrentUser();
                if (current.email() == null || !email.equalsIgnoreCase(current.email())) {
                    throw new BusinessException(ResultCode.CAPTCHA_EMAIL_MISMATCH);
                }
            }
            case FORGOT_PASSWORD -> {
                // 防枚举：邮箱未注册时**不落码、不投递**，对外仍是「发送成功」，
                // 与已注册邮箱的响应完全一致。顺带避免本接口被打成垃圾邮件发射器
                //（向任意地址投递的公开接口，是现成的滥用面）。
                if (userService.getByEmail(email) == null) {
                    log.info("重置密码发码：邮箱未注册，静默跳过（对外仍返回成功，防枚举）");
                    return null;
                }
            }
        }

        return captchaService.send(scene, email, clientIp);
    }

    @Override
    public void register(RegisterRequest request) {
        // 邮箱唯一性校验（逻辑删除的用户不入库此邮箱，可正常重新注册）
        if (userService.getByEmail(request.getEmail()) != null) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
        }

        // 验证码校验刻意放在唯一性之后，两个理由：
        // ① 校验成功即用后即焚——若先校验，已注册邮箱的重试会把用户手里那张有效的码烧掉，
        //    用户得重新等 60 秒才能再拿一张；
        // ② 保持「重复注册返回 1001」的既有语义，不会因为缺码/错码先撞上验证码错误。
        captchaService.verify(CaptchaScene.REGISTER, request.getEmail(), request.getCaptcha());

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname())
                ? request.getNickname() : UserConstants.defaultNickname(request.getEmail()));
        user.setStatus(0); // 默认正常
        userService.save(user);
    }

    @Override
    public LoginVO login(LoginRequest request) {
        Authentication authentication;
        try {
            // 由 Spring Security 完成认证（会判断账号是否禁用）
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (DisabledException e) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        } catch (BadCredentialsException e) {
            throw new BusinessException(ResultCode.EMAIL_OR_PASSWORD_ERROR);
        }

        // 认证过程中已查过库，直接复用 principal 中的用户行，省掉一次冗余查询
        AuthUserAccount account = ((SecurityUser) authentication.getPrincipal()).getAccount();

        // 签发双 token（Redis 为唯一真相源）；同用户旧会话的 at/rt 立即失效（单端登录）
        AuthSession session = authSessionService.issue(account.id());

        // 角色码/权限码从缓存读取（认证链路已回填，此处直接命中）
        PermissionCacheData data = permissionCacheService.load(account.id());

        LoginVO vo = new LoginVO();
        vo.setToken(session.accessToken());
        vo.setRefreshToken(session.refreshToken());
        vo.setUserId(account.id());
        vo.setEmail(account.email());
        vo.setNickname(account.nickname());
        vo.setRoles(data.getRoles());
        vo.setPermissions(data.getPermissions());
        return vo;
    }

    @Override
    public RefreshVO refresh(RefreshRequest request) {
        String rawRefreshToken = request.getRefreshToken();

        // 1. 只读反查身份（不消耗令牌）：refresh token 自身即身份来源，无需客户端自报 userId
        Long userId;
        try {
            userId = authSessionService.findUserIdByRefreshToken(rawRefreshToken);
        } catch (Exception e) {
            // 与第 3 步 rotate 保持同一契约：Redis 故障一律 1019（fail-closed）。
            // 否则同一次故障落在不同步骤会分别返回 500 / 1019，前端行为不一致
            log.error("刷新令牌反查失败（Redis 异常，fail-closed）", e);
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        // 2. 实时查库兜底（@TableLogic 自动过滤已删用户）：不存在或被禁用则拒绝续期。
        //    有意放在轮换【之前】：若先轮换再查库，一旦查库失败（数据库抖动），旧令牌已被销毁、
        //    新令牌又没发给客户端，等于白白烧掉用户的有效会话——数据库短时抖动会造成全体被迫重登。
        User user = userMapper.selectById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() != 0)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        // 3. 原子轮换（脚本内重验 rt、双保险比对 session、绝对上限判定；Redis 异常 fail-closed 1019）
        //    此处刻意不做「轮换失败再补偿删除」：第 2 步与第 3 步之间用户被禁用的极端情况无害——
        //    新签发的 AT 每个请求都要过一次查库 isEnabled()，对已禁用用户是一张废纸；
        //    而补偿式删除会误删并发建立的新会话，且查库抛异常时补偿根本不会执行，反而留下孤儿会话。
        AuthSession session = authSessionService.rotate(rawRefreshToken);

        RefreshVO vo = new RefreshVO();
        vo.setToken(session.accessToken());
        vo.setRefreshToken(session.refreshToken());
        return vo;
    }

    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        // 一致性校验放 Service（不放 DTO 的 @AssertTrue）：DTO 校验失败会统一压成 400，
        // 拿不到 1030 这个专用业务码
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ResultCode.PASSWORD_NOT_MATCH);
        }

        // 验证码校验：未注册邮箱在发码阶段就被静默跳过（不落码），此处必然落到 MISS，
        // 与「码已过期 / 填错」返回同一个 1028 与同一句文案 —— 天然不泄露邮箱是否已注册
        captchaService.verify(CaptchaScene.FORGOT_PASSWORD, request.getEmail(), request.getCaptcha());

        User user = userService.getByEmail(request.getEmail());
        if (user == null) {
            // 走到这里只有一种可能：发码之后、重置之前用户被删了（并发窗口）。
            // 同样返回 1028 而不是「用户不存在」，避免把邮箱注册状态泄露出去
            throw new BusinessException(ResultCode.CAPTCHA_INVALID);
        }

        userService.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()));

        // 重置密码与改密同属「止损事件」：旧密码可能已泄露，必须注销该用户全部会话，
        // 否则已流出的令牌最长还能续期到 30 天绝对上限（五入口统一收口，不开例外）。
        // 失败只 ERROR 告警不阻断：密码已经改成功了，此时把接口报错反而让用户以为重置失败
        try {
            authSessionService.remove(user.getId());
        } catch (Exception e) {
            log.error("重置密码后清理会话失败：旧令牌仍有效，止损未生效, userId={}", user.getId(), e);
        }
    }

    @Override
    public void logout() {
        Long userId = SecurityUtils.getCurrentUserId();
        try {
            // 清理 at/rt/session 三键：access token 立即失效
            authSessionService.remove(userId);
        } catch (Exception e) {
            // 登出以用户意图为准：Redis 异常降级为成功，避免登出界面卡死。
            // 注意此时服务端会话未真正清除（旧 AT 在有效期内仍可用），仅告警留痕
            log.warn("登出清理会话失败（服务端会话可能未清除）, userId={}", userId, e);
        }
    }

    @Override
    public UserVO me() {
        AuthUserAccount account = SecurityUtils.getCurrentUser();
        return UserVO.from(account);
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        AuthUserAccount account = SecurityUtils.getCurrentUser();

        boolean byOldPassword = StringUtils.hasText(request.getOldPassword());
        boolean byCaptcha = StringUtils.hasText(request.getCaptcha());
        // 二选一：都传或都不传一律拒绝。都传时必须拒绝——否则无法判断用户意图，
        // 更糟的是会让「两段校验里漏实现一段」的代码照样通过（另一段兜住了）。
        if (byOldPassword == byCaptcha) {
            throw new BusinessException(ResultCode.CHANGE_PASSWORD_CHANNEL_REQUIRED);
        }

        if (byOldPassword) {
            if (!passwordEncoder.matches(request.getOldPassword(), account.password())) {
                throw new BusinessException(ResultCode.OLD_PASSWORD_ERROR);
            }
        } else {
            // 通道 B：验证码只能发到本人邮箱（发码时已强制），故这里用当前登录用户的邮箱校验。
            // 安全边界明示：此通道下「邮箱可达 = 凭据可改」，靠改密后统一销毁会话做止损
            captchaService.verify(CaptchaScene.CHANGE_PASSWORD, account.email(), request.getCaptcha());
        }

        userService.updatePassword(account.id(), passwordEncoder.encode(request.getNewPassword()));

        // 改密后强制重新登录：旧密码可能已泄露，继续沿用已签发的令牌会让改密失去止损意义。
        // 必须在密码落库成功之后调用，否则改密失败也会把用户踢下线。
        // 客户端契约：本接口返回成功后，当前令牌即失效，前端应引导重新登录。
        try {
            authSessionService.remove(account.id());
        } catch (Exception e) {
            // ERROR 级：与禁用不同，改密没有任何「每请求查库」的兜底网
            //（refresh 的查库只看 deleted/status，不看密码），此处失败即意味着
            //「改密止损」未生效——旧 refresh token 最长仍可续期 7 天。必须可告警、可检索。
            log.error("改密后清理会话失败：旧令牌仍有效，改密止损未生效, userId={}", account.id(), e);
        }
    }
}