package com.qsx.service;

import com.qsx.common.result.PageResult;
import com.qsx.web.dto.query.PermissionQuery;
import com.qsx.web.vo.PermissionVO;

import java.util.List;

/**
 * 权限管理服务（只读）
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
}