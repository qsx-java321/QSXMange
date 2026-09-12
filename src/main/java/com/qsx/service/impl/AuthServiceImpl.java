package com.qsx.service.impl;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.token.JwtTokenProvider;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.AuthService;
import com.qsx.service.PermissionCacheService;
import com.qsx.service.RefreshTokenService;
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
    private final JwtTokenProvider jwtTokenProvider;
    private final PermissionCacheService permissionCacheService;
    private final RefreshTokenService refreshTokenService;

    public AuthServiceImpl(UserService userService,
                           UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtTokenProvider jwtTokenProvider,
                           PermissionCacheService permissionCacheService,
                           RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.permissionCacheService = permissionCacheService;
        this.refreshTokenService = refreshTokenService;
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
        try {
            // 由 Spring Security 完成认证（会判断账号是否禁用）
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (DisabledException e) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        } catch (BadCredentialsException e) {
            throw new BusinessException(ResultCode.EMAIL_OR_PASSWORD_ERROR);
        }

        User user = userService.getByEmail(request.getEmail());
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail());
        // 签发 refresh token 并写入 Redis（单端登录：新登录覆盖旧会话）
        String refreshToken = refreshTokenService.issue(user.getId());

        // 角色码/权限码从缓存读取（authenticate 已回填，此处直接命中）
        PermissionCacheData data = permissionCacheService.load(user.getId());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setRefreshToken(refreshToken);
        vo.setUserId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setRoles(data.getRoles());
        vo.setPermissions(data.getPermissions());
        return vo;
    }

    @Override
    public RefreshVO refresh(RefreshRequest request) {
        // 实时查库（MyBatis-Plus @TableLogic 自动过滤已删用户）：用户不存在/被删直接拒绝续期
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        // 禁用的用户拒绝续期（安全兜底：即使禁用时未删 refresh key 也不泄密）
        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        // 校验并轮换 refresh token（hash 不匹配 / 超 30 天 / Redis 异常均 fail-closed）
        String newRefreshToken = refreshTokenService.rotate(request.getUserId(), request.getRefreshToken());

        RefreshVO vo = new RefreshVO();
        vo.setToken(jwtTokenProvider.generateToken(user.getId(), user.getEmail()));
        vo.setRefreshToken(newRefreshToken);
        return vo;
    }

    @Override
    public void logout() {
        Long userId = SecurityUtils.getCurrentUserId();
        try {
            refreshTokenService.remove(userId);
        } catch (Exception e) {
            // 登出以用户意图为准：Redis 异常降级为成功，避免登出界面卡死
            log.warn("登出删除刷新会话失败, userId={}", userId, e);
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
    }
}