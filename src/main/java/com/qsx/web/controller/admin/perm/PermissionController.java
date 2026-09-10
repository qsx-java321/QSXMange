package com.qsx.web.controller.admin.perm;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.Result;
import com.qsx.service.PermissionService;
import com.qsx.web.dto.query.PermissionQuery;
import com.qsx.web.vo.PermissionVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理接口（只读，权限码维护走 init.sql）
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.PERM_PAGE + "')")
    public Result<PageResult<PermissionVO>> page(PermissionQuery query) {
        return Result.success(permissionService.page(query));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('" + PermissionConstants.PERM_PAGE + "')")
    public Result<List<PermissionVO>> listAll() {
        return Result.success(permissionService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.PERM_GET + "')")
    public Result<PermissionVO> getById(@PathVariable Long id) {
        return Result.success(permissionService.getById(id));
    }
}