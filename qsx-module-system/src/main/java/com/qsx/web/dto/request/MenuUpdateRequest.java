package com.qsx.web.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    /**
     * 类型取值白名单为 {@code MENU}/{@code PERMISSION} 两个字面量，其中 {@code MENU} 与
     * {@link com.qsx.common.constant.PermissionConstants#TYPE_MENU} 同值。
     * 修改路径此前完全无约束，填错会让菜单树按 {@code type == "MENU"} 过滤时静默失配。
     * 注意与 {@link MenuCreateRequest} 的差别：新增留空表示取默认 MENU，修改为必填。
     */
    @NotNull(message = "类型不能为空")
    @Pattern(regexp = "MENU|PERMISSION", message = "类型只能为 MENU 或 PERMISSION")
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
    @Min(value = 0, message = "是否显示只能为 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "是否显示只能为 0(隐藏) 或 1(显示)")
    private Integer visible;

    @NotNull(message = "排序不能为空")
    @Min(value = 0, message = "排序取值范围为 0~9999")
    @Max(value = 9999, message = "排序取值范围为 0~9999")
    private Integer sort;
}
