package com.qsx.web.dto.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

/**
 * Excel 批量导入用户行模型（模板列：邮箱/昵称/状态/角色编码）
 */
@Data
@ColumnWidth(20)
public class UserImportRow {

    /** 邮箱（必填，格式校验 + 全库唯一） */
    @ExcelProperty("邮箱")
    private String email;

    /** 昵称（选填，空则默认取邮箱） */
    @ExcelProperty("昵称")
    private String nickname;

    /** 状态（选填：0-正常 / 1-禁用，空默认 0） */
    @ExcelProperty("状态")
    private String status;

    /** 角色编码（选填，多个用英文逗号分隔，如 ADMIN,USER） */
    @ExcelProperty("角色编码")
    private String roleCodes;

    /** 实际数据行号（表头为第 1 行，数据从第 2 行起），校验错误定位用，不参与读写 */
    @ExcelIgnore
    private Integer rowNum;
}
