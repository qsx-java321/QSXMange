package com.qsx.service;

import com.qsx.framework.result.PageResult;
import com.qsx.web.dto.query.PermissionQuery;
import com.qsx.web.dto.request.MenuCreateRequest;
import com.qsx.web.dto.request.MenuUpdateRequest;
import com.qsx.web.vo.PermissionVO;

import java.util.List;

/**
 * 权限管理服务（菜单 + 按钮权限）
 */
public interface PermissionService {

    /**
     * 分页查询权限
     */
    PageResult<PermissionVO> page(PermissionQuery query);

    /**
     * 权限详情
     */
    PermissionVO getById(Long id);

    /**
     * 查询全部权限（用于为角色分配权限）
     */
    List<PermissionVO> listAll();

    /**
     * 全量菜单/权限树（管理端）
     */
    List<PermissionVO> tree();

    /**
     * 新增菜单或按钮权限
     */
    PermissionVO create(MenuCreateRequest request);

    /**
     * 修改菜单或按钮权限
     */
    PermissionVO update(Long id, MenuUpdateRequest request);

    /**
     * 删除菜单或按钮权限（有子节点禁止删除，并清理角色-权限关联）
     */
    void delete(Long id);

    /**
     * 当前登录用户的菜单树（按权限过滤并补齐祖先，供前端动态路由）
     */
    List<PermissionVO> getUserMenuTree();
}
