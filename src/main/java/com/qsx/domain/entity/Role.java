package com.qsx.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.qsx.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统角色表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class Role extends BaseEntity {

    /** 角色编码（如 ADMIN），需唯一 */
    private String code;

    /** 角色名称 */
    private String name;

    /** 角色描述 */
    private String description;

    /** 状态：0-启用，1-停用 */
    private Integer status;
}