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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务实现。
 *
 * <p>业务职责：编排认证域全部用例——注册、登录、刷新令牌、登出、忘记密码重置、
 * 修改密码（旧密码/验证码双通道）与邮箱验证码发送。
 *
 * <p>使用场景：{@code AuthController} 下全部 {@code /auth/**} 端点的业务入口；
 * 其中 {@code logout}/{@code me}/{@code changePassword} 依赖请求上下文中的登录态。
 *
 * <p>核心依赖：{@link UserService}（用户读写）、{@link AuthSessionService}（Redis 双 token
 * 会话，令牌有效性的唯一真相源）、{@link PermissionCacheService}（角色/权限码）、
 * {@link CaptchaService}（验证码发送与校验）、Spring Security 的 {@link AuthenticationManager}
 * 与 {@link PasswordEncoder}。
 *
 * <p>事务与幂等：本类自身不声明事务，依赖被调方法既有的事务边界；注册的「查重→插入」
 * 并发窗口由 uk_email 兜底并映射回业务码，刷新令牌经会话层 Lua 原子轮换、同一令牌不可双花。
 *
 * <p>失败策略：会话链路 fail-closed（Redis 故障拒绝签发/轮换）；登出、改密与密码重置
 * 后的会话清理失败仅 ERROR 告警不阻断（业务语义优先，但需可告警、可检索）。
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

    /**
     * 按场景发送邮箱验证码（对外接口 {@code POST /auth/captcha}）。
     *
     * <p>三种场景的前置校验不同：{@code REGISTER} 要求邮箱未被占用（与注册同码）；
     * {@code CHANGE_PASSWORD} 要求已登录且邮箱为本人（本接口 URL 层 permitAll，未登录时
     * 由 {@link SecurityUtils} 抛未登录业务码）；{@code FORGOT_PASSWORD} 对未注册邮箱
     * 不落码、不投递、静默返回，对外与已注册邮箱的成功响应完全一致（防枚举）。
     * 投递失败不阻断：码已落 Redis，用户可在发送间隔过后重发。
     *
     * @param request  发码请求（场景 + 邮箱，字段合法性由 DTO 校验保证）
     * @param clientIp 客户端 IP，用于 IP 日限流；允许为空/空白（归一为 {@code unknown}，
     *                 避免与真实 IP 撞键）
     * @return 仅调试模式（{@code qsx.captcha.debug=true}）返回验证码明文供联调；
     *         正常路径恒返回 {@code null}
     * @throws BusinessException 注册场景邮箱已占用（{@link ResultCode#EMAIL_ALREADY_REGISTERED}）、
     *         改密场景邮箱与当前登录用户不一致（{@link ResultCode#CAPTCHA_EMAIL_MISMATCH}）、
     *         触发间隔/日限限流（{@link ResultCode#CAPTCHA_SEND_TOO_FREQUENT}）
     */
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

    /**
     * 自助注册：校验邮箱唯一性与验证码后落库，账号默认状态正常。
     *
     * <p>校验顺序刻意是「唯一性 → 验证码」：验证码校验成功即用后即焚，若先校验，
     * 已注册邮箱的重试会把用户手里那张有效的码烧掉；同时保持「重复注册返回邮箱已注册」
     * 的既有语义。昵称缺省时按邮箱生成默认昵称；「查重 → 插入」之间的并发窗口由
     * uk_email 兜住并映射为同一业务码。自注册口令由用户自选，不置「强制首次改密」。
     *
     * @param request 注册请求（邮箱 + 密码 + 验证码，昵称可空）
     * @throws BusinessException 邮箱已注册（{@link ResultCode#EMAIL_ALREADY_REGISTERED}）、
     *         验证码无效（{@link ResultCode#CAPTCHA_INVALID}）或错误次数超限
     *         （{@link ResultCode#CAPTCHA_ATTEMPT_EXCEEDED}）
     */
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
        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 「查重 → 插入」之间的并发窗口（同一邮箱并发/双击注册）：uk_email 兜住了一致性，
            // 但异常直穿会变成 body 500「系统繁忙」。映射回与查重一致的业务码
            throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    /**
     * 账号密码登录：认证通过后签发双 token，并返回用户信息与角色/权限码。
     *
     * <p>认证由 Spring Security 完成（含禁用判定）；签发使同用户旧会话的 at/rt 立即失效
     * （单端登录）。登录本身不因「强制首次改密」标志失败，只把标志回传给前端，
     * 由令牌过滤器在后续请求上拦截。
     *
     * @param request 登录请求（邮箱 + 密码）
     * @return 登录结果：双 token、用户基础信息、角色码/权限码、强制改密标志
     * @throws BusinessException 账号被禁用（{@link ResultCode#USER_DISABLED}）或
     *         邮箱/密码错误（{@link ResultCode#EMAIL_OR_PASSWORD_ERROR}）
     */
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
        // 登录本身**不**因该标志而失败：不登录就没有会话，没有会话就改不了密码。
        // 拦截发生在 TokenAuthenticationFilter，对 /auth/** 整体豁免
        vo.setMustChangePassword(account.mustChangePassword());
        return vo;
    }

    /**
     * 刷新令牌：以 refresh token 为身份来源，实时查库兜底后原子轮换出一对新 token。
     *
     * <p>关键顺序为「只读反查身份 → 查库确认用户存在且未禁用 → 原子轮换」：查库刻意
     * 放在轮换之前，避免数据库抖动时烧掉用户的有效会话；轮换由会话层 Lua 原子完成
     * （重验 rt、双保险比对 session、绝对上限判定）。刷新本身不因强制改密标志失败。
     *
     * @param request 刷新请求（仅 refresh token）
     * @return 新的 access/refresh token 与强制改密标志
     * @throws BusinessException 令牌缺失/无效/已过期/超绝对上限，或 Redis 故障，
     *         统一 {@link ResultCode#REFRESH_TOKEN_INVALID}（fail-closed）
     */
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
        // 上面第 2 步已经把整行取出来了，回传标志零额外开销；刷新本身**不**因该标志失败
        vo.setMustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()));
        return vo;
    }

    /**
     * 忘记密码重置：校验两次密码一致性与验证码后更新口令，并注销该用户全部会话。
     *
     * <p>未注册邮箱在发码阶段即被静默跳过（不落码），此处必然落到验证码无效，
     * 与「码过期/填错」返回同一结果，不泄露邮箱是否注册；发码后用户被删的并发窗口
     * 同样按验证码无效处理。重置成功后的会话清理失败仅 ERROR 告警不阻断
     * （密码已改成功的语义优先，但需可告警、可检索）。
     *
     * @param request 重置请求（邮箱 + 新密码 + 确认密码 + 验证码）
     * @throws BusinessException 两次密码不一致（{@link ResultCode#PASSWORD_NOT_MATCH}）、
     *         验证码无效（{@link ResultCode#CAPTCHA_INVALID}）或错误次数超限
     *         （{@link ResultCode#CAPTCHA_ATTEMPT_EXCEEDED}）
     */
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

    /**
     * 登出：清理当前用户的 at/rt/session 三键，access token 立即失效。
     *
     * <p>以用户意图为准：Redis 异常降级为成功（仅 WARN 告警），避免登出界面卡死；
     * 代价是服务端会话可能未真正清除（旧 AT 在有效期内仍可用）。
     *
     * @throws BusinessException 未登录时由 {@link SecurityUtils#getCurrentUserId()} 抛出
     *         （{@link ResultCode#UNAUTHORIZED}）
     */
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

    /**
     * 获取当前登录用户的信息（基础资料快照，不含角色/权限码）。
     *
     * @return 当前用户信息的视图对象
     * @throws BusinessException 未登录时由 {@link SecurityUtils#getCurrentUser()} 抛出
     *         （{@link ResultCode#UNAUTHORIZED}）
     */
    @Override
    public UserVO me() {
        AuthUserAccount account = SecurityUtils.getCurrentUser();
        return UserVO.from(account);
    }

    /**
     * 修改当前登录用户的密码：支持「旧密码」与「邮箱验证码」两种通道且必须二选一。
     *
     * <p>都传或都不传一律拒绝：无法判断用户意图，且双通道会让「任一段校验漏实现」的
     * 代码仍被另一段兜住。改密成功后强制注销全部会话（止损：旧凭据可能已泄露），
     * 客户端须重新登录；会话清理在密码落库成功之后执行，失败仅 ERROR 告警不阻断。
     *
     * @param request 改密请求（旧密码 或 验证码 二选一 + 新密码；验证码通道要求
     *                发送场景为 {@code CHANGE_PASSWORD} 且码发往本人邮箱）
     * @throws BusinessException 通道未提供或同时提供
     *         （{@link ResultCode#CHANGE_PASSWORD_CHANNEL_REQUIRED}）、旧密码错误
     *         （{@link ResultCode#OLD_PASSWORD_ERROR}）、验证码无效或超限、未登录时
     *         由 {@link SecurityUtils} 抛出（{@link ResultCode#UNAUTHORIZED}）
     */
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