package com.qsx.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.qsx.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统权限表（权限码模型）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class Permission extends BaseEntity {

    /** 权限标识（如 user:add），需唯一 */
    private String code;

    /** 权限名称 */
    private String name;

    /** 类型：MENU/PERMISSION */
    private String type;

    /** 排序 */
    private Integer sort;
}