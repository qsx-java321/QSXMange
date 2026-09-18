package com.qsx.web.controller.admin.menu;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.result.Result;
import com.qsx.service.PermissionService;
import com.qsx.web.dto.request.MenuCreateRequest;
import com.qsx.web.dto.request.MenuUpdateRequest;
import com.qsx.web.vo.PermissionVO;
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
 * 菜单管理接口（菜单与按钮权限共用 sys_permission 表）
 */
@RestController
@RequestMapping("/api/menus")
public class MenuController {

    private final PermissionService permissionService;

    public MenuController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('" + PermissionConstants.MENU_TREE + "')")
    public Result<List<PermissionVO>> tree() {
        return Result.success(permissionService.tree());
    }

    @GetMapping("/current")
    public Result<List<PermissionVO>> current() {
        return Result.success(permissionService.getUserMenuTree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.MENU_CREATE + "')")
    public Result<PermissionVO> create(@Valid @RequestBody MenuCreateRequest request) {
        return Result.success(permissionService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.MENU_UPDATE + "')")
    public Result<PermissionVO> update(@PathVariable Long id, @Valid @RequestBody MenuUpdateRequest request) {
        return Result.success(permissionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.MENU_DELETE + "')")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }
}
