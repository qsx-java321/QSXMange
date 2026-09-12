package com.qsx.web.controller.admin.user;

import com.qsx.common.constant.PermissionConstants;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.service.UserImportExportService;
import com.qsx.web.dto.excel.ImportResult;
import com.qsx.web.dto.query.UserQuery;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 用户 Excel 批量导入导出接口（需登录 + 相应权限）
 */
@RestController
@RequestMapping("/api/users")
public class UserImportExportController {

    private final UserImportExportService importExportService;

    public UserImportExportController(UserImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /**
     * 下载导入模板（仅表头：邮箱/昵称/状态/角色编码）
     */
    @GetMapping("/import/template")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_IMPORT + "')")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        importExportService.downloadTemplate(response);
    }

    /**
     * 批量导入用户（整批校验：任一数据行不合法则整体拒绝，返回错误明细）
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_IMPORT + "')")
    public Result<ImportResult> importUsers(@RequestParam("file") MultipartFile file) throws IOException {
        ImportResult result = importExportService.importUsers(file);
        if (result.getErrors() != null && !result.getErrors().isEmpty()) {
            return Result.fail(ResultCode.IMPORT_VALIDATE_FAILED.getCode(),
                    ResultCode.IMPORT_VALIDATE_FAILED.getMessage(), result);
        }
        return Result.success(result);
    }

    /**
     * 按筛选条件导出用户列表（不含密码）
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_EXPORT + "')")
    public void exportUsers(UserQuery query, HttpServletResponse response) throws IOException {
        importExportService.exportUsers(query, response);
    }
}
