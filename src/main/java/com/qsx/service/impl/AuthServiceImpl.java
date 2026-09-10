package com.qsx.service.impl;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.User;
import com.qsx.mapper.UserMapper;
import com.qsx.security.token.JwtTokenProvider;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.AuthService;
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
    private final UserMapper userMapper;

    public AuthServiceImpl(UserService userService,
                           PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           JwtTokenProvider jwtTokenProvider,
                           UserMapper userMapper) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userMapper = userMapper;
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

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setEmail(user.getEmail());
        vo.setNickname(user.getNickname());
        vo.setRoles(userMapper.selectRoleCodes(user.getId()));
        vo.setPermissions(userMapper.selectPermissionCodes(user.getId()));
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