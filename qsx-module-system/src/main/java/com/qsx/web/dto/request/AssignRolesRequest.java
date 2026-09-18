package com.qsx.web.dto.request;

import lombok.Data;

import java.util.List;

/**
 * 为用户分配角色请求（整表替换，空列表=清空全部）
 */
@Data
public class AssignRolesRequest {

    /** 角色ID集合（整表替换；空集合表示清空该用户全部角色） */
    private List<Long> roleIds;
}