package com.qsx.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 角色新增请求
 */
@Data
public class RoleCreateRequest {

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 64, message = "角色编码长度不能超过64")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 50, message = "角色名称长度不能超过50")
    private String name;

    @Size(max = 255, message = "角色描述长度不能超过255")
    private String description;

    /** 状态：0-启用，1-停用，默认启用 */
    private Integer status;
}