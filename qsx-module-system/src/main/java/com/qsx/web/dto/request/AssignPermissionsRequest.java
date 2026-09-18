package com.qsx.web.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 为角色分配权限请求（整表替换，空列表=清空全部）
 */
@Data
public class AssignPermissionsRequest {

    /** 权限ID集合（整表替换；空集合表示清空该角色全部权限） */
    private List<Long> permissionIds;
}