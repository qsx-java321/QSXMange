package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.service.UserService;
import com.qsx.web.dto.query.UserQuery;
import com.qsx.web.dto.request.UserCreateRequest;
import com.qsx.web.dto.request.UserUpdateRequest;
import com.qsx.web.vo.UserVO;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String DELETED_EMAIL_SUFFIX_PREFIX = "#deleted_";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           UserRoleMapper userRoleMapper,
                           RoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
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
        user.setStatus(request.getStatus());
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    @Override
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
    }

    @Override
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