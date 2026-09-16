package com.qsx.service.impl;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.session.AuthSession;
import com.qsx.security.session.AuthSessionService;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.AuthService;
import com.qsx.service.PermissionCacheService;
import com.qsx.service.UserService;
import com.qsx.web.dto.request.ChangePasswordRequest;
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

    public AuthServiceImpl(UserService userService,
                           UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           AuthSessionService authSessionService,
                           PermissionCacheService permissionCacheService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.authSessionService = authSessionService;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public void register(RegisterRequest request) {
        // 邮箱唯一性校验（逻辑删除的用户不入库此邮箱，可正常重新注册）
        if (userService.getByEmail(request.getEmail()) != null) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getEmail());
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
        User user = ((SecurityUser) authentication.getPrincipal()).getUser();

        // 签发双 token（Redis 为唯一真相源）；同用户旧会话的 at/rt 立即失效（单端登录）
        AuthSession session = authSessionService.issue(user.getId());

        // 角色码/权限码从缓存读取（认证链路已回填，此处直接命中）
        PermissionCacheData data = permissionCacheService.load(user.getId());

        LoginVO vo = new LoginVO();
        vo.setToken(session.accessToken());
        vo.setRefreshToken(session.refreshToken());
        vo.setUserId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setRoles(data.getRoles());
        vo.setPermissions(data.getPermissions());
        return vo;
    }

    @Override
    public RefreshVO refresh(RefreshRequest request) {
        String rawRefreshToken = request.getRefreshToken();

        // 1. 只读反查身份（不消耗令牌）：refresh token 自身即身份来源，无需客户端自报 userId
        Long userId = authSessionService.findUserIdByRefreshToken(rawRefreshToken);
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
        User current = SecurityUtils.getCurrentUser();
        return UserVO.from(current);
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        User current = SecurityUtils.getCurrentUser();
        if (!passwordEncoder.matches(request.getOldPassword(), current.getPassword())) {
            throw new BusinessException(ResultCode.OLD_PASSWORD_ERROR);
        }
        userService.updatePassword(current.getId(), passwordEncoder.encode(request.getNewPassword()));

        // 改密后强制重新登录：旧密码可能已泄露，继续沿用已签发的令牌会让改密失去止损意义。
        // 必须在密码落库成功之后调用，否则改密失败也会把用户踢下线。
        // 客户端契约：本接口返回成功后，当前令牌即失效，前端应引导重新登录。
        try {
            authSessionService.remove(current.getId());
        } catch (Exception e) {
            log.warn("改密后清理会话失败（旧令牌仍可用至过期）, userId={}", current.getId(), e);
        }
    }
}