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
import com.qsx.security.session.AuthSessionService;
import com.qsx.security.util.SecurityUtils;
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

    /**
     * 请求是否在禁用账号：<b>非 0 即禁用</b>。
     * 必须与 {@code SecurityUser.isEnabled()}（status==0 才放行）以及
     * {@code AuthServiceImpl.refresh} 的查库兜底用同一谓词，否则会出现
     * 「按 A 谓词禁用、按 B 谓词清理」的错位。
     */
    private static boolean isDisabling(Integer status) {
        return status != null && status != 0;
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthSessionService authSessionService;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper,
                           ApplicationEventPublisher eventPublisher,
                           AuthSessionService authSessionService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.eventPublisher = eventPublisher;
        this.authSessionService = authSessionService;
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

        // 禁用保护：请求禁用时——禁止禁用自己、超管不可禁用，防止误操作锁死系统。
        // 判定用「非 0 即禁用」，与 SecurityUser.isEnabled()（status==0 才放行）保持同一谓词：
        // 两套谓词错位时，写非 0/1 的其它值可绕过保护并跳过会话清理（DTO 已限 0/1，此处为纵深防御）
        if (isDisabling(request.getStatus())) {
            if (id.equals(SecurityUtils.getCurrentUserId())) {
                throw new BusinessException(ResultCode.CANNOT_OPERATE_SELF);
            }
            if (userMapper.selectRoleCodes(id).contains(RoleConstants.ADMIN)) {
                throw new BusinessException(ResultCode.ADMIN_USER_CANNOT_DISABLE);
            }
        }
        user.setStatus(request.getStatus());
        userMapper.updateById(user);

        // 禁用即踢下线：清理会话三键，旧 access token 立即失效。
        // 注意谓词是「非 0 即禁用」而非「== 1」：普通资料编辑带 status=0 不会触发，
        // 而任何能真正禁用账号的取值都必须清理会话，否则解冻后旧令牌会复活。
        // 仅告警不阻断：禁用语义优先，且每请求查库 isEnabled() 是第二道防线
        if (isDisabling(request.getStatus())) {
            try {
                authSessionService.remove(id);
            } catch (Exception e) {
                log.warn("禁用用户时清理会话失败, userId={}", id, e);
            }
        }
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

        // 同步清理会话（at/rt/session 三键）；Redis 异常仅告警，不阻断删除（删除语义优先）
        try {
            authSessionService.remove(id);
        } catch (Exception e) {
            log.warn("删除用户时清理会话失败, userId={}", id, e);
        }

        // 事务提交后失效该用户权限缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUser(id));
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
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUser(userId));
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
        // 清理目标会话（at/rt/session 三键）：旧 access token 立即失效；
        // Redis 异常不静默（fail-closed），由全局兜底返回 500
        authSessionService.remove(id);
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