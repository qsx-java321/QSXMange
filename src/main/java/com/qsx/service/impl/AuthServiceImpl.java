package com.qsx.service.impl;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.User;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.token.JwtTokenProvider;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.AuthService;
import com.qsx.service.PermissionCacheService;
import com.qsx.service.UserService;
import com.qsx.web.dto.request.ChangePasswordRequest;
import com.qsx.web.dto.request.LoginRequest;
import com.qsx.web.dto.request.RegisterRequest;
import com.qsx.web.vo.LoginVO;
import com.qsx.web.vo.UserVO;
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

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final PermissionCacheService permissionCacheService;

    public AuthServiceImpl(UserService userService,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtTokenProvider jwtTokenProvider,
                           PermissionCacheService permissionCacheService) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
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

        // 角色码/权限码从缓存读取（authenticate 已回填，此处直接命中）
        PermissionCacheData data = permissionCacheService.load(user.getId());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setRoles(data.getRoles());
        vo.setPermissions(data.getPermissions());
        return vo;
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