package com.qsx.web.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    /**
     * 状态：0-启用，1-停用，默认启用。取值限定必须与 {@link RoleUpdateRequest} 一致——
     * 权限查询按 `r.status = 0` 过滤，放任 2 等取值会让角色既不启用也不可解释；
     * 只在校验「修改」时限定，等于允许从「新增」绕过去
     */
    @Min(value = 0, message = "状态只能为 0(启用) 或 1(停用)")
    @Max(value = 1, message = "状态只能为 0(启用) 或 1(停用)")
    private Integer status;
}