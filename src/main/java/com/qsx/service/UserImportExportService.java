package com.qsx.service;

import com.qsx.web.dto.excel.ImportResult;
import com.qsx.web.dto.query.UserQuery;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Excel 批量导入导出服务
 */
public interface UserImportExportService {

    /**
     * 下载用户导入模板（仅表头）
     */
    void downloadTemplate(HttpServletResponse response) throws IOException;

    /**
     * 批量导入用户：整批校验，任一数据行不合法则整体拒绝（不落任何数据）
     *
     * @return 导入结果（successCount 入库条数，errors 非空表示整批被拒绝）
     */
    ImportResult importUsers(MultipartFile file) throws IOException;

    /**
     * 按筛选条件导出用户列表（不含密码）
     */
    void exportUsers(UserQuery query, HttpServletResponse response) throws IOException;
}
