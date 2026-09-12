package com.qsx.web.dto.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * Excel 批量导出用户行模型（不包含任何密码等敏感字段）
 */
@Data
@ColumnWidth(20)
public class UserExportRow {

    /** 邮箱 */
    @ExcelProperty("邮箱")
    private String email;

    /** 昵称 */
    @ExcelProperty("昵称")
    private String nickname;

    /** 状态（正常/禁用） */
    @ExcelProperty("状态")
    private String status;

    /** 角色编码（逗号分隔） */
    @ExcelProperty("角色编码")
    private String roleCodes;

    /** 创建时间（yyyy-MM-dd HH:mm:ss） */
    @ExcelProperty("创建时间")
    private String createTime;
}
