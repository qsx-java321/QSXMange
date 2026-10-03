package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.constant.RoleConstants;
import com.qsx.common.exception.BusinessException;
import com.qsx.framework.result.PageResult;
import com.qsx.common.result.ResultCode;
import com.qsx.security.event.PermissionCacheEvictEvent;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色管理服务实现。
 *
 * <p>业务职责：角色分页/详情、创建、更新（名称/描述/状态）、删除、权限分配与下拉列表。
 *
 * <p>使用场景：{@code RoleController} 管理端接口；用户侧的角色分配与超管身份判定
 * 依赖本模块维护的角色数据。
 *
 * <p>核心依赖：{@link RoleMapper}/{@link RolePermissionMapper}/{@link UserRoleMapper}/
 * {@link PermissionMapper}（持久层）、{@link ApplicationEventPublisher}（提交后失效
 * 受影响用户的权限缓存）。
 *
 * <p>事务与一致性：update/delete/assignPermissions 声明事务；受影响用户必须在改动关联表
 * 之前反查（缓存失效监听器在提交后才执行，届时关联行已删、再查必然为空）。
 *
 * <p>保护闸：角色编码创建后不可修改；内置超管角色不可删除、不可停用、其权限绑定不可
 * 整体替换——三道闸都是防「系统失去管理能力」而非防误操作。
 */
@Service
public class RoleServiceImpl implements RoleService {

    /**
     * 内置超管编码，禁止删除 / 禁止停用 / 禁止改其权限绑定。
     *
     * <p>必须取自 {@link RoleConstants#ADMIN} 而非字面量：1011/1026/1036 与本项目其它
     * 超管判定（{@code UserServiceImpl.isBuiltInAdmin}、导入侧的闸）都要收敛到同一个真源，
     * 否则改了常量会出现"某几道闸跟着变、另几道不跟着变"的不对称保护。
     */
    private static final String BUILT_IN_ADMIN_CODE = RoleConstants.ADMIN;

    /**
     * 状态「非 0 即停用」。
     * 必须与权限查询的 {@code r.status = 0} 过滤用同一谓词，否则会出现
     * 「按 A 谓词停用、按 B 谓词仍授权」的错位。
     */
    private static boolean isDisabling(Integer status) {
        return status != null && status != 0;
    }

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

    /**
     * 分页查询角色列表。
     *
     * <p>过滤条件均可空：编码/名称模糊匹配，状态精确匹配；结果按 id 倒序。
     *
     * @param query 分页查询条件（页码、页大小、可选的编码/名称/状态）
     * @return 分页结果，每行为角色视图对象（不含权限关联明细）
     */
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

    /**
     * 按主键查询角色详情（含已绑定的权限 ID 列表）。
     *
     * @param id 角色 ID
     * @return 角色视图对象（permissionIds 为当前绑定集合）
     * @throws BusinessException 角色不存在（{@link ResultCode#ROLE_NOT_FOUND}）
     */
    @Override
    public RoleVO getById(Long id) {
        Role role = getRole(id);
        RoleVO vo = RoleVO.from(role);
        vo.setPermissionIds(selectPermissionIds(id));
        return vo;
    }

    /**
     * 创建角色。
     *
     * <p>编码唯一性校验刻意包含逻辑删除行（物理唯一索引不含 deleted 列，删除后同编码
     * 重建会撞索引）；状态缺省为正常；「查重 → 插入」之间的并发窗口由 uk_role_code
     * 兜住并映射为同一业务码。
     *
     * @param request 创建请求（编码 + 名称，描述/状态可空）
     * @return 新建角色的视图对象
     * @throws BusinessException 角色编码已存在（{@link ResultCode#ROLE_CODE_EXISTS}，
     *         含已被逻辑删除的历史编码）
     */
    @Override
    public RoleVO create(RoleCreateRequest request) {
        // 角色编码唯一性校验：刻意包含逻辑删除行——uk_role_code 是物理唯一索引，
        // 用 BaseMapper 判重会被 @TableLogic 自动过滤掉已删除行，
        // 于是「删除角色后用同一编码重建」通过校验、在 INSERT 时撞唯一索引变成 500
        if (roleMapper.countByCodeIncludeDeleted(request.getCode()) > 0) {
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS);
        }

        Role role = new Role();
        role.setCode(request.getCode());
        role.setName(request.getName());
        role.setDescription(request.getDescription());
        role.setStatus(request.getStatus() == null ? 0 : request.getStatus());
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            // 判重与插入之间的并发窗口（双击/并发建角色）：uk_role_code 兜住了一致性，
            // 但异常直穿会变成 body 500「系统繁忙」。映射回与判重一致的业务码
            throw new BusinessException(ResultCode.ROLE_CODE_EXISTS);
        }
        return RoleVO.from(role);
    }

    /**
     * 更新角色（名称/描述/状态）。
     *
     * <p>编码必须与原值一致——编码是 RBAC 权威标识，且内置超管保护按编码匹配，
     * 改名会让保护静默失配。内置超管角色不可停用（停用会让仅经 ADMIN 取权的账号
     * 立即失去全部权限，且可能无人能再启用它）。更新前反查该角色下用户，事务提交后
     * 失效其权限缓存（状态/名称变更会影响菜单树等派生结果）。
     *
     * @param id      角色 ID
     * @param request 更新请求（编码须与原值一致；名称/描述/状态）
     * @return 更新后的角色视图对象
     * @throws BusinessException 角色不存在（{@link ResultCode#ROLE_NOT_FOUND}）、
     *         尝试修改编码（{@link ResultCode#ROLE_CODE_IMMUTABLE}）、
     *         停用内置超管角色（{@link ResultCode#ADMIN_ROLE_CANNOT_DISABLE}）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO update(Long id, RoleUpdateRequest request) {
        Role role = getRole(id);
        // 先反查该角色下用户（状态/名称变更需失效其缓存），供事务提交后失效
        Set<Long> affectedUserIds = new HashSet<>(userRoleMapper.selectUserIdsByRoleId(id));

        // 角色编码创建后不可修改：它是 RBAC 权威标识（ROLE_xxx），且内置超管保护依赖
        // RoleConstants.ADMIN 按编码匹配——改名会让「超管不可踢/不可禁用」的保护静默失配，
        // 等于给了一条把超管权限保护绕过去的路径。需要改名请新建角色并重新分配
        if (!request.getCode().equals(role.getCode())) {
            throw new BusinessException(ResultCode.ROLE_CODE_IMMUTABLE);
        }

        // 内置超管角色不可停用：权限查询按 r.status = 0 过滤，停用后所有「仅经 ADMIN 角色
        // 获得权限」的用户会立即失去全部权限——包括分配权限所需的 role:assign 与 role:update。
        // 若此时无人持有其它带 role:update 的角色，就再没有人能通过接口把 ADMIN 角色启用回来，
        // 系统被锁死在「无人可管理权限」的状态，只能直接改库恢复。
        // 与 delete() 里的「超管角色不可删除」对称：一个防删，一个防停，都是防失控而非防误操作。
        if (BUILT_IN_ADMIN_CODE.equals(role.getCode()) && isDisabling(request.getStatus())) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_CANNOT_DISABLE);
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

    /**
     * 删除角色：级联解除权限关联与用户关联后逻辑删除。
     *
     * <p>内置超管角色禁止删除（避免误删导致失控）。普通角色会先物理删除
     * 角色-权限、用户-角色关联，再逻辑删除角色本体；受影响用户必须在删关联之前反查，
     * 事务提交后失效其权限缓存。
     *
     * @param id 角色 ID
     * @throws BusinessException 角色不存在（{@link ResultCode#ROLE_NOT_FOUND}）、
     *         内置超管角色不可删除（{@link ResultCode#ROLE_IN_USE}）
     */
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

    /**
     * 为角色分配权限（整表替换：先物理删旧关联，再批量插新关联）。
     *
     * <p>内置超管角色的权限绑定不可整体替换（清空后连恢复所需的 role:assign 一起消失，
     * 只能改库），幂等重存同样拒绝——判定只看目标角色身份，不看请求内容。其余情况先
     * 校验目标权限均存在，再在改动关联前反查该角色下用户，事务提交后失效其权限缓存。
     *
     * @param roleId        角色 ID
     * @param permissionIds 权限 ID 列表；{@code null} 或空列表表示清空该角色全部权限
     * @throws BusinessException 角色不存在（{@link ResultCode#ROLE_NOT_FOUND}）、
     *         目标为内置超管角色（{@link ResultCode#ADMIN_ROLE_PERMISSION_IMMUTABLE}）、
     *         权限不存在（{@link ResultCode#PERMISSION_NOT_FOUND}）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        Role role = getRole(roleId);
        // 闸：内置超管角色的权限绑定不可整体替换——与 1011（不可删）/ 1026（不可停用）对称，
        // 同一判定源、同一理由：清空 ADMIN 的权限后，所有"仅经 ADMIN 取权"的账号立刻失去全部权限，
        // **连恢复所需的 role:assign 一起消失**，而角色仍启用、code 不可改（1024）、不可删不可停
        // ⇒ 只能改库。它比删角色/停角色更隐蔽：管理端「编辑 ADMIN 角色 → 保存」即可触发。
        // 幂等重存（传当前同一集合）同样拒绝：判定只看目标角色身份，不看请求内容。
        // 位置在 getRole 之后、反查受影响用户之前：保证被拒的请求不产生任何副作用
        //（不跑 N 行 DISTINCT 查询、不动关联表、更不会发布 AFTER_COMMIT 的缓存失效事件——
        //  否则"拒绝了却顺手清掉全站超管的权限缓存"，一旦监听器改成非事务绑定就会成真）。
        if (BUILT_IN_ADMIN_CODE.equals(role.getCode())) {
            throw new BusinessException(ResultCode.ADMIN_ROLE_PERMISSION_IMMUTABLE);
        }
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

    /**
     * 查询全部启用角色（下拉选项用），按 id 升序。
     *
     * <p>刻意只返回 status = 0 的角色，与权限查询的过滤谓词保持一致。
     *
     * @return 启用角色的视图对象列表；无数据时为空列表
     */
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

    private List<Long> selectPermissionIds(Long roleId) {
        return rolePermissionMapper.selectList(
                        new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId))
                .stream().map(RolePermission::getPermissionId).collect(Collectors.toList());
    }
}