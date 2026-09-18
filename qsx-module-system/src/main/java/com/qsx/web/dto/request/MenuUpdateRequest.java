package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单修改请求
 */
@Data
public class MenuUpdateRequest {

    @NotBlank(message = "菜单标识不能为空")
    @Size(max = 64, message = "菜单标识长度不能超过64")
    private String code;

    @NotBlank(message = "菜单名称不能为空")
    @Size(max = 50, message = "菜单名称长度不能超过50")
    private String name;

    @NotNull(message = "类型不能为空")
    private String type;

    /** 父ID：0-顶级 */
    private Long parentId;

    @Size(max = 200, message = "路由地址长度不能超过200")
    private String path;

    @Size(max = 200, message = "组件路径长度不能超过200")
    private String component;

    @Size(max = 50, message = "图标长度不能超过50")
    private String icon;

    @NotNull(message = "是否显示不能为空")
    private Integer visible;

    @NotNull(message = "排序不能为空")
    private Integer sort;
}
