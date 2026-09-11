package com.qsx.service;

import com.qsx.common.result.PageResult;
import com.qsx.domain.entity.OperationLog;
import com.qsx.web.dto.query.LogQuery;
import com.qsx.web.vo.OperationLogVO;

/**
 * 操作日志服务
 */
public interface OperationLogService {

    /**
     * 异步落库一条操作日志（独立线程，失败不影响主请求）
     */
    void asyncSave(OperationLog operationLog);

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