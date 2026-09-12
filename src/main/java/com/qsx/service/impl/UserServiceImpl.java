package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.constant.RoleConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.config.event.PermissionCacheEvictEvent;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.RefreshTokenService;
import com.qsx.service.UserService;
import com.qsx.web.dto.query.UserQuery;
import com.qsx.web.dto.request.UserCreateRequest;
import com.qsx.web.dto.request.UserUpdateRequest;
import com.qsx.web.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    private static final String DELETED_EMAIL_SUFFIX_PREFIX = "#deleted_";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RefreshTokenService refreshTokenService;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           ApplicationEventPublisher eventPublisher,
                           RefreshTokenService refreshTokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.eventPublisher = eventPublisher;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .like(StringUtils.hasText(query.getEmail()), User::getEmail, query.getEmail())
                .like(StringUtils.hasText(query.getNickname()), User::getNickname, query.getNickname())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .orderByDesc(User::getId);

        Page<User> page = userMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        return PageResult.of(page.convert(UserVO::from));
    }

    @Override
    public UserVO getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return UserVO.from(user);
    }

    @Override
    public UserVO create(UserCreateRequest request) {
        // 邮箱唯一性校验
        if (getByEmail(request.getEmail()) != null) {
            throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getEmail());
        user.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        userMapper.insert(user);
        return UserVO.from(user);
    }

    @Override
    public UserVO update(Long id, UserUpdateRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 邮箱被修改时校验唯一性（排除自己）
        if (!request.getEmail().equals(user.getEmail())) {
            User existing = getByEmail(request.getEmail());
            if (existing != null && !existing.getId().equals(id)) {
                throw new BusinessException(ResultCode.EMAIL_ALREADY_REGISTERED);
            }
        }

        user.setEmail(request.getEmail());
        user.setNickname(request.getNickname());

        // 禁用保护：status=1（禁用）时——禁止禁用自己、超管不可禁用，防止误操作锁死系统
        if (request.getStatus() != null && request.getStatus() == 1) {
            if (id.equals(SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
            }
            if (userMapper.selectRoleCodes(id).contains(RoleConstants.ADMIN)) {
                throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_DISABLE);
            }
        }
        user.setStatus(request.getStatus());
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        // 释放邮箱：在逻辑删除前，将原邮箱拼接删除时间戳后缀，
        // 避免与原邮箱的唯一索引冲突，从而允许同一个邮箱再次注册
        String suffixedEmail = user.getEmail() + DELETED_EMAIL_SUFFIX_PREFIX + System.currentTimeMillis();
        user.setEmail(suffixedEmail);
        userMapper.updateById(user);

        // 逻辑删除（deleted -> 1）
        userMapper.deleteById(id);

        // 同步清理刷新会话；Redis 异常仅告警，不阻断删除（删除语义优先）
        try {
            refreshTokenService.remove(id);
        } catch (Exception e) {
            log.warn("删除用户时清理刷新会话失败, userId={}", id, e);
        }

        // 事务提交后失效该用户权限缓存
        eventPublisher.publishEvent(new PermissionCacheEvictEvent(PermissionCacheEvictEvent.Type.USER, id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        // 用户存在性校验
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 校验目标角色均存在
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                if (roleMapper.selectById(roleId) == null) {
                    throw new BusinessException(ResultCode.ROLE_NOT_FOUND);
                }
            }
        }
        // 整表替换：先物理删旧，再批量插新
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            List<UserRole> list = roleIds.stream()
                    .distinct()
                    .map(roleId -> {
                        UserRole ur = new UserRole();
                        ur.setUserId(userId);
                        ur.setRoleId(roleId);
                        ur.setCreateTime(now);
                        return ur;
                    })
                    .collect(Collectors.toList());
            userRoleMapper.insertBatch(list);
        }

        // 事务提交后失效该用户权限缓存
        eventPublisher.publishEvent(new PermissionCacheEvictEvent(PermissionCacheEvictEvent.Type.USER, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void kick(Long id) {
        if (userMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 不允许踢自己（操作者的会话由自己登出）
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
        }
        // 内置超管不可被强制登出，避免误操作导致系统失去掌控
        if (userMapper.selectRoleCodes(id).contains(RoleConstants.ADMIN)) {
            throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_KICK);
        }
        // 删除目标刷新会话；Redis 异常不静默（fail-closed），由全局兜底返回 500
        refreshTokenService.remove(id);
    }

    @Override
    public User getByEmail(String email) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    @Override
    public void save(User user) {
        userMapper.insert(user);
    }

    @Override
    public void updatePassword(Long id, String encodedPassword) {
        User user = new User();
        user.setId(id);
        user.setPassword(encodedPassword);
        userMapper.updateById(user);
    }
}