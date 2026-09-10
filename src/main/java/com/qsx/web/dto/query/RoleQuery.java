package com.qsx.web.dto.query;

import lombok.Data;

/**
 * 角色分页查询条件
 */
@Data
public class RoleQuery {

    private String code;
    private String name;
    /** 状态：0-启用，1-停用，null则全部 */
    private Integer status;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
}