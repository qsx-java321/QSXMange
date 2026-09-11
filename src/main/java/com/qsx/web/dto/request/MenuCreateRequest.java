package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单新增请求
 */
@Data
public class MenuCreateRequest {

    @NotBlank(message = "菜单标识不能为空")
    @Size(max = 64, message = "菜单标识长度不能超过64")
    private String code;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 50, message = "菜单名称长度不能超过50")
    private String name;

    /** 类型：MENU-菜单 / PERMISSION-按钮权限，默认 MENU */
    private String type;

    /** 父ID：0-顶级，默认 0 */
    private Long parentId;

    @Size(max = 200, message = "路由地址长度不能超过200")
    private String path;

    @Size(max = 200, message = "组件路径长度不能超过200")
    private String component;

    @Size(max = 50, message = "图标长度不能超过50")
    private String icon;

    /** 是否显示：1-显示，0-隐藏，默认 1 */
    private Integer visible;

    /** 排序（同级内升序），默认 0 */
    private Integer sort;
}
