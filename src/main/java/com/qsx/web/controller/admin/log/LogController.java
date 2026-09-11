package com.qsx.web.controller.admin.log;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.result.PageResult;
import com.qsx.common.result.Result;
import com.qsx.service.OperationLogService;
import com.qsx.web.dto.query.LogQuery;
import com.qsx.web.vo.OperationLogVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志管理接口（需登录 + 相应权限）
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final OperationLogService operationLogService;

    public LogController(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.LOG_PAGE + "')")
    public Result<PageResult<OperationLogVO>> page(LogQuery query) {
        return Result.success(operationLogService.page(query));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionConstants.LOG_DELETE + "')")
    public Result<Void> deleteById(@PathVariable Long id) {
        operationLogService.deleteById(id);
        return Result.success();
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('" + PermissionConstants.LOG_DELETE + "')")
    public Result<Void> clear() {
        operationLogService.clear();
        return Result.success();
    }
}