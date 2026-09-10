package com.qsx.service;

import com.qsx.common.result.PageResult;
import com.qsx.web.dto.query.RoleQuery;
import com.qsx.web.dto.request.RoleCreateRequest;
import com.qsx.web.dto.request.RoleUpdateRequest;
import com.qsx.web.vo.RoleVO;

import java.util.List;

/**
 * 角色管理服务
 */
public interface RoleService {

    /**
     * 分页查询角色
     */
    PageResult<RoleVO> page(RoleQuery query);

    /**
     * 角色详情（含已分配权限ID）
     */
    RoleVO getById(Long id);

    /**
     * 新增角色
     */
    RoleVO create(RoleCreateRequest request);

    /**
     * 修改角色
     */
    RoleVO update(Long id, RoleUpdateRequest request);

    /**
     * 删除角色（逻辑删除，先物理清角色-权限/用户-角色关联）
     */
    void delete(Long id);

    /**
     * 为角色分配权限（整表替换）
     */
    void assignPermissions(Long roleId, List<Long> permissionIds);

    /**
     * 查询全部启用角色（用于分配用户角色）
     */
    List<RoleVO> listAll();
}