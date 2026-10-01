package com.qsx.web.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    /**
     * 类型：MENU-菜单 / PERMISSION-按钮权限，默认 MENU（留空表示用默认值）。
     *
     * <p>取值白名单必须与 {@link com.qsx.common.constant.PermissionConstants} 的
     * {@code TYPE_MENU}/{@code TYPE_PERMISSION} 保持同步（注解里写不了常量，只能手工同步）。
     * 此前是自由字符串，而菜单树按 {@code type == "MENU"} 过滤 ⇒ 写错一个字符（如 "menu"）
     * 会让该菜单对所有人的菜单树**静默消失**。
     */
    @Pattern(regexp = "(MENU|PERMISSION)?", message = "类型只能为 MENU 或 PERMISSION")
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
    @Min(value = 0, message = "是否显示只能为 0(隐藏) 或 1(显示)")
    @Max(value = 1, message = "是否显示只能为 0(隐藏) 或 1(显示)")
    private Integer visible;

    /** 排序（同级内升序），默认 0 */
    @Min(value = 0, message = "排序取值范围为 0~9999")
    @Max(value = 9999, message = "排序取值范围为 0~9999")
    private Integer sort;
}
