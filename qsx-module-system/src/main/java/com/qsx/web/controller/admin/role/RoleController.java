package com.qsx.web.controller.admin.role;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.framework.result.PageResult;
import com.qsx.common.result.Result;
import com.qsx.service.RoleService;
import com.qsx.web.dto.query.RoleQuery;
import com.qsx.web.dto.request.AssignPermissionsRequest;
import com.qsx.web.dto.request.RoleCreateRequest;
import com.qsx.web.dto.request.RoleUpdateRequest;
import com.qsx.web.vo.RoleVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_PAGE + "')")
    public Result<PageResult<RoleVO>> page(RoleQuery query) {
        return Result.success(roleService.page(query));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_PAGE + "')")
    public Result<List<RoleVO>> listAll() {
        return Result.success(roleService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_GET + "')")
    public Result<RoleVO> getById(@PathVariable Long id) {
        return Result.success(roleService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_CREATE + "')")
    public Result<RoleVO> create(@Valid @RequestBody RoleCreateRequest request) {
        return Result.success(roleService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_UPDATE + "')")
    public Result<RoleVO> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        return Result.success(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_DELETE + "')")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('" + PermissionConstants.ROLE_ASSIGN_PERM + "')")
    public Result<Void> assignPermissions(@PathVariable Long id,
                                          @Valid @RequestBody AssignPermissionsRequest request) {
        roleService.assignPermissions(id, request.getPermissionIds());
        return Result.success();
    }
}