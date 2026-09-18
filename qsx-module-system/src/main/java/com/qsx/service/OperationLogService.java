package com.qsx.service;

import com.qsx.framework.result.PageResult;
import com.qsx.web.dto.query.LogQuery;
import com.qsx.web.vo.OperationLogVO;

/**
 * 操作日志服务
 *
 * 写入侧由 {@link com.qsx.common.log.AccessLogRecorder} 承担（本接口不再暴露落库方法），
 * 本接口只负责查询与维护。
 */
public interface OperationLogService {

    /**
     * 分页查询操作日志
     */
    PageResult<OperationLogVO> page(LogQuery query);

    /**
     * 删除单条日志
     */
    void deleteById(Long id);

    /**
     * 清空全部日志
     */
    void clear();
}