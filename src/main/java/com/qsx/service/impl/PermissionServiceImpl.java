package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.config.event.PermissionCacheEvictEvent;
import com.qsx.domain.entity.Permission;
import com.qsx.domain.entity.RolePermission;
import com.qsx.domain.entity.User;
import com.qsx.mapper.PermissionMapper;
import com.qsx.mapper.RolePermissionMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.util.SecurityUtils;
import com.qsx.service.PermissionCacheService;
import com.qsx.service.PermissionService;
import com.qsx.web.dto.query.PermissionQuery;
import com.qsx.web.dto.request.MenuCreateRequest;
import com.qsx.web.dto.request.MenuUpdateRequest;
import com.qsx.web.vo.PermissionVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限管理服务实现（菜单 + 按钮权限）
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    /** 顶级菜单父ID */
    private static final long ROOT_PARENT_ID = 0L;

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionCacheService permissionCacheService;
    private final ApplicationEventPublisher eventPublisher;

    public PermissionServiceImpl(PermissionMapper permissionMapper,
                                 RolePermissionMapper rolePermissionMapper,
                                 UserRoleMapper userRoleMapper,
                                 PermissionCacheService permissionCacheService,
                                 ApplicationEventPublisher eventPublisher) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionCacheService = permissionCacheService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public PageResult<PermissionVO> page(PermissionQuery query) {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<Permission>()
                .like(StringUtils.hasText(query.getCode()), Permission::getCode, query.getCode())
                .like(StringUtils.hasText(query.getName()), Permission::getName, query.getName())
                .orderByAsc(Permission::getSort);

        Page<Permission> page = permissionMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(PermissionVO::from));
    }

    @Override
    public PermissionVO getById(Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.PERMISSION_NOT_FOUND);
        }
        return PermissionVO.from(permission);
    }

    @Override
    public List<PermissionVO> listAll() {
        return permissionMapper.selectList(
                        new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getSort))
                .stream().map(PermissionVO::from).collect(Collectors.toList());
    }

    @Override
    public List<PermissionVO> tree() {
        return buildTree(selectAll());
    }

    @Override
    public PermissionVO create(MenuCreateRequest request) {
        // 标识唯一性校验（菜单/按钮共用 code 唯一索引）
        if (selectByCode(request.getCode()) != null) {
            throw new BusinessException(ResultCode.PERMISSION_CODE_EXISTS);
        }

        Long parentId = request.getParentId() == null ? ROOT_PARENT_ID : request.getParentId();
        validateParent(parentId, null);

        Permission permission = new Permission();
        permission.setCode(request.getCode());
        permission.setName(request.getName());
        permission.setType(StringUtils.hasText(request.getType())
                ? request.getType() : PermissionConstants.TYPE_MENU);
        permission.setParentId(parentId);
        permission.setPath(request.getPath());
        permission.setComponent(request.getComponent());
        permission.setIcon(request.getIcon());
        permission.setVisible(request.getVisible() == null ? 1 : request.getVisible());
        permission.setSort(request.getSort() == null ? 0 : request.getSort());
        permissionMapper.insert(permission);
        return PermissionVO.from(permission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PermissionVO update(Long id, MenuUpdateRequest request) {
        Permission permission = getPermission(id);
        // 先反查持有者：菜单名/可见性等变更会影响菜单树，须在改动前取快照并失效其缓存；
        // 反查须在改动关联前完成，且本方法必须有事务，否则 AFTER_COMMIT 监听器不会触发
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByPermissionId(id));

        // 标识（code）创建后不可修改：它既是 @PreAuthorize 的鉴权依据，也是菜单树的过滤依据。
        // 改错一个系统码（如 menu:update）会让对应接口全部失配——**包括把它改回来所需的那个接口**，
        // 只能靠改库恢复。需要改名请新建一条并重新绑定角色
        if (!request.getCode().equals(permission.getCode())) {
            throw new BusinessException(ResultCode.PERMISSION_CODE_IMMUTABLE);
        }
        validateParent(request.getParentId(), id);

        permission.setCode(request.getCode());
        permission.setName(request.getName());
        permission.setType(request.getType());
        permission.setParentId(request.getParentId() == null ? ROOT_PARENT_ID : request.getParentId());
        permission.setPath(request.getPath());
        permission.setComponent(request.getComponent());
        permission.setIcon(request.getIcon());
        permission.setVisible(request.getVisible());
        permission.setSort(request.getSort());
        permissionMapper.updateById(permission);

        // 事务提交后失效持有该菜单/权限的全部用户缓存
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUsers(affectedUserIds));
        return PermissionVO.from(permission);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getPermission(id);
        // 存在子节点禁止删除，防止孤立子树
        Long childCount = permissionMapper.selectCount(
                new LambdaQueryWrapper<Permission>().eq(Permission::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(ResultCode.MENU_HAS_CHILDREN);
        }
        // 先反查持有该菜单/权限的用户：紧接着角色-权限关联就会被物理删除，必须在此之前取快照，
        // 否则事务提交后已无从反查，被删权限码将在缓存中继续通过 @PreAuthorize 直至 TTL 到期
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByPermissionId(id));

        // 清理角色-权限关联，再逻辑删除
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, id));
        permissionMapper.deleteById(id);

        // 事务提交后失效持有该权限的全部用户缓存（菜单/权限删除影响其下所有持有者）
        eventPublisher.publishEvent(PermissionCacheEvictEvent.ofUsers(affectedUserIds));
    }

    @Override
    public List<PermissionVO> getUserMenuTree() {
        User current = SecurityUtils.getCurrentUser();
        // 权限码从缓存读取（认证过滤器已回填，此处直接命中）
        PermissionCacheData data = permissionCacheService.load(current.getId());
        List<String> ownedCodes = data.getPermissions();
        if (ownedCodes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> owned = new HashSet<>(ownedCodes);

        List<Permission> allMenus = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>()
                        .eq(Permission::getType, PermissionConstants.TYPE_MENU)
                        .orderByAsc(Permission::getSort)
                        .orderByAsc(Permission::getId));
        if (allMenus.isEmpty()) {
            return Collections.emptyList();
        }

        // 保留：用户有权限的菜单 + 其全部祖先链（无直接权限也能看到父菜单入口）
        Map<Long, Permission> byId = allMenus.stream()
                .collect(Collectors.toMap(Permission::getId, p -> p));
        Set<Long> keepIds = new HashSet<>();
        for (Permission menu : allMenus) {
            if (owned.contains(menu.getCode())) {
                Long cur = menu.getId();
                while (cur != null && cur != ROOT_PARENT_ID && keepIds.add(cur)) {
                    Permission ancestor = byId.get(cur);
                    if (ancestor == null) {
                        break;
                    }
                    cur = ancestor.getParentId();
                }
            }
        }
        if (keepIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Permission> kept = allMenus.stream()
                .filter(menu -> keepIds.contains(menu.getId()))
                .collect(Collectors.toList());
        return buildTree(kept);
    }

    // ---------- 私有辅助 ----------

    private List<Permission> selectAll() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>()
                        .orderByAsc(Permission::getSort)
                        .orderByAsc(Permission::getId));
    }

    /**
     * 按 parentId 组装树（保持传入列表的排序）
     */
    private List<PermissionVO> buildTree(List<Permission> all) {
        Map<Long, List<Permission>> byParent = all.stream()
                .collect(Collectors.groupingBy(p -> p.getParentId() == null ? ROOT_PARENT_ID : p.getParentId()));
        return buildChildren(ROOT_PARENT_ID, byParent);
    }

    private List<PermissionVO> buildChildren(Long parentId, Map<Long, List<Permission>> byParent) {
        return byParent.getOrDefault(parentId, Collections.emptyList()).stream()
                .map(p -> {
                    PermissionVO vo = PermissionVO.from(p);
                    vo.setChildren(buildChildren(p.getId(), byParent));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    private Permission getPermission(Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new BusinessException(ResultCode.MENU_NOT_FOUND);
        }
        return permission;
    }

    private Permission selectByCode(String code) {
        return permissionMapper.selectOne(new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
    }

    /**
     * 校验父节点：必须是已存在的菜单，且不能把节点挂到自身或其子孙下（防环）
     */
    private void validateParent(Long parentId, Long excludeId) {
        if (parentId == null || parentId == ROOT_PARENT_ID) {
            return;
        }
        Permission parent = permissionMapper.selectById(parentId);
        if (parent == null || !PermissionConstants.TYPE_MENU.equals(parent.getType())) {
            throw new BusinessException(ResultCode.MENU_PARENT_INVALID);
        }
        if (excludeId != null) {
            Set<Long> visited = new HashSet<>();
            Long cur = parentId;
            while (cur != null && cur != ROOT_PARENT_ID) {
                if (cur.equals(excludeId)) {
                    throw new BusinessException(ResultCode.MENU_PARENT_INVALID);
                }
                if (!visited.add(cur)) {
                    break; // 防御脏数据死循环
                }
                Permission ancestor = permissionMapper.selectById(cur);
                cur = ancestor == null ? null : ancestor.getParentId();
            }
        }
    }
}
