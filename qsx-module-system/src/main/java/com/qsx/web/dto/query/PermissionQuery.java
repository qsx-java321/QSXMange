package com.qsx.web.dto.query;

import lombok.Data;

/**
 * 权限分页查询条件
 */
@Data
public class PermissionQuery {

    private String code;
    private String name;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
}