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

    /** 权限标识（菜单/按钮，如 system-user / user:add），需唯一 */
    private String code;

    /** 权限名称 */
    private String name;

    /** 类型：MENU-菜单 / PERMISSION-按钮权限 */
    private String type;

    /** 父ID：0-顶级；按钮权限指向所属菜单ID */
    private Long parentId;

    /** 菜单路由地址 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 菜单图标 */
    private String icon;

    /** 是否显示：1-显示，0-隐藏 */
    private Integer visible;

    /** 排序（同级内升序） */
    private Integer sort;
}