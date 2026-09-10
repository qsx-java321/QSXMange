package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.RolePermission;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.PermissionMapper;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.RolePermissionMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.service.RoleService;
import com.qsx.web.dto.query.RoleQuery;
import com.qsx.web.dto.request.RoleCreateRequest;
import com.qsx.web.dto.request.RoleUpdateRequest;
import com.qsx.web.vo.RoleVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色管理服务实现
 */
@Service
public class RoleServiceImpl implements RoleService {

    /** 内置超管编码，禁止删除 */
    private static final String BUILT_IN_ADMIN_CODE = "ADMIN";

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;

    public RoleServiceImpl(RoleMapper roleMapper,
                           RolePermissionMapper rolePermissionMapper,
                           UserRoleMapper userRoleMapper,
                           PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public PageResult<RoleVO> page(RoleQuery query) {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<Role>()
                .like(StringUtils.hasText(query.getCode()), Role::getCode, query.getCode())
                .like(StringUtils.hasText(query.getName()), Role::getName, query.getName())
                .eq(query.getStatus() != null, Role::getStatus, query.getStatus())
                .orderByDesc(Role::getId);

        Page<Role> page = roleMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(RoleVO::from));
    }

    @Override
    public RoleVO getById(Long id) {
        Role role = getRole(id);
        RoleVO vo = RoleVO.from(role);
        vo.setPermissionIds(selectPermissionIds(id));
        return vo;
    }

    @Override
    public RoleVO create(RoleCreateRequest request) {
        // 角色编码唯一性校验（穷比校验 request code 是否已存在，含逻辑删除库存量）
        if (selectByCode(request.getCode()) != null) {
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS);
        }

        Role role = new Role();
        role.setCode(request.getCode());
        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        roleMapper.insert(role);
        return RoleVO.from(role);
    }

    @Override
    public RoleVO update(Long id, RoleUpdateRequest request) {
        Role role = getRole(id);
        // 编码被修改时校验唯一性（排除自身）
        if (!request.getCode().equals(role.getCode())) {
            Role existing = selectByCode(request.getCode());
            if (existing != null && !existing.getId().equals(id)) {
                throw new BusinessException(ResultCode.ROLE_CODE_EXISTS);
            }
        }
        role.setCode(request.getCode());
        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus());
        roleMapper.updateById(role);
        return RoleVO.from(role);
    }

    @Override
    public void delete(Long id) {
        Role role = getRole(id);
        // 内置超管角色禁止删除，避免误删全部权限导致失控
        if (BUILT_IN_ADMIN_CODE.equals(role.getCode())) {
            throw new BusinessException(ResultCode.ROLE_IN_USE);
        }
        // 先物理清关联，再逻辑删除角色
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id));
        roleMapper.deleteById(id);
    }

    @Override
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        getRole(roleId);
        // 校验目标权限均存在
        if (permissionIds != null) {
            for (Long permId : permissionIds) {
                if (permissionMapper.selectById(permId) == null) {
                    throw new BusinessException(ResultCode.PERMISSION_NOT_FOUND);
                }
            }
        }
        // 整表替换：先物理删旧，再批量插新
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        if (permissionIds != null && !permissionIds.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            List<RolePermission> list = permissionIds.stream()
                    .distinct()
                    .map(permId -> {
                        RolePermission rp = new RolePermission();
                        rp.setRoleId(roleId);
                        rp.setPermissionId(permId);
                        rp.setCreateTime(now);
                        return rp;
                    })
                    .collect(Collectors.toList());
            rolePermissionMapper.insertBatch(list);
        }
    }

    @Override
    public List<RoleVO> listAll() {
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getStatus, 0)
                        .orderByAsc(Role::getId));
        return roles.stream().map(RoleVO::from).collect(Collectors.toList());
    }

    private Role getRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(ResultCode.ROLE_NOT_FOUND);
        }
        return role;
    }

    private Role selectByCode(String code) {
        return roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getCode, code));
    }

    private List<Long> selectPermissionIds(Long roleId) {
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId))
                .stream().map(RolePermission::getPermissionId).collect(Collectors.toList());
    }
}