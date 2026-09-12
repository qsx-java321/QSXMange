package com.qsx.web.controller.admin.user;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.Result;
import com.qsx.service.UserService;
import com.qsx.web.dto.query.UserQuery;
import com.qsx.web.dto.request.AssignRolesRequest;
import com.qsx.web.dto.request.UserCreateRequest;
import com.qsx.web.dto.request.UserUpdateRequest;
import com.qsx.web.vo.UserVO;
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

/**
 * 用户管理接口（需登录 + 相应权限）
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_PAGE + "')")
    public Result<PageResult<UserVO>> page(UserQuery query) {
        return Result.success(userService.page(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_GET + "')")
    public Result<UserVO> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_CREATE + "')")
    public Result<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success(userService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_UPDATE + "')")
    public Result<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return Result.success(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_DELETE + "')")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_ASSIGN_ROLE + "')")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRolesRequest request) {
        userService.assignRoles(id, request.getRoleIds());
        return Result.success();
    }

    @PostMapping("/{id}/kick")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_KICK + "')")
    public Result<Void> kick(@PathVariable Long id) {
        userService.kick(id);
        return Result.success();
    }
}