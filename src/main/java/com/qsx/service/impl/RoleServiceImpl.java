package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.config.event.PermissionCacheEvictEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final ApplicationEventPublisher eventPublisher;

    public RoleServiceImpl(RoleMapper roleMapper,
                           RolePermissionMapper rolePermissionMapper,
                           UserRoleMapper userRoleMapper,
                           PermissionMapper permissionMapper,
                           ApplicationEventPublisher eventPublisher) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.eventPublisher = eventPublisher;
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
    @Transactional(rollbackFor = Exception.class)
    public RoleVO update(Long id, RoleUpdateRequest request) {
        Role role = getRole(id);
        // 先反查该角色下用户（编码变更影响其 ROLE_xxx authority），供事务提交后失效
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByRoleId(id));
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

        // 事务提交后失效其下全部用户缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUsers(affectedUserIds));
        return RoleVO.from(role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Role role = getRole(id);
        // 内置超管角色禁止删除，避免误删全部权限导致失控
        if (BUILT_IN_ADMIN_CODE.equals(role.getCode())) {
            throw new BusinessException(ResultCode.ROLE_IN_USE);
        }
        // 先反查该角色下用户：紧接着关联行就会被物理删除，必须在此之前取快照，
        // 否则事务提交后已无从反查，其权限缓存将残留至 TTL 到期
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByRoleId(id));

        // 先物理清关联，再逻辑删除角色
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id));
        roleMapper.deleteById(id);

        // 事务提交后失效该角色下全部用户缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUsers(affectedUserIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        // 先反查该角色下用户：授权即将整表替换，须在改动关联前取快照
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByRoleId(roleId));

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

        // 事务提交后失效该角色下全部用户缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUsers(affectedUserIds));
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