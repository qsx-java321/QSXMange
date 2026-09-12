package com.qsx.web.dto.excel;

import lombok.Data;

import java.util.List;

/**
 * Excel 批量导入结果
 */
@Data
public class ImportResult {

    /** 实际入库条数（整批拒绝时为 0） */
    private int successCount;

    /** 校验失败明细（如“第 3 行：邮箱格式不正确”），非空表示整批拒绝 */
    private List<String> errors;
}
