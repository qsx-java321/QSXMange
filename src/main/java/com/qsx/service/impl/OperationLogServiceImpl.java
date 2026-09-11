package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.result.PageResult;
import com.qsx.domain.entity.OperationLog;
import com.qsx.mapper.OperationLogMapper;
import com.qsx.service.OperationLogService;
import com.qsx.web.dto.query.LogQuery;
import com.qsx.web.vo.OperationLogVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 操作日志服务实现
 */
@Slf4j
@Service
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Override
    @Async("operationLogExecutor")
    public void asyncSave(OperationLog operationLog) {
        try {
            operationLogMapper.insert(operationLog);
        } catch (Exception e) {
            // 异步独立线程：日志落库失败不影响主业务请求，仅记录告警
            log.error("操作日志落库失败: userId={}, url={}, cost={}ms",
                    operationLog.getUserId(), operationLog.getUrl(), operationLog.getCostMs(), e);
        }
    }

    @Override
    public PageResult<OperationLogVO> page(LogQuery query) {
        LocalDateTime begin = query.getBeginTime();
        LocalDateTime end = query.getEndTime();
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<OperationLog>()
                .like(StringUtils.hasText(query.getUsername()), OperationLog::getUsername, query.getUsername())
                .like(StringUtils.hasText(query.getUrl()), OperationLog::getUrl, query.getUrl())
                .eq(query.getSuccess() != null, OperationLog::getSuccess, query.getSuccess())
                .eq(StringUtils.hasText(query.getMethod()), OperationLog::getMethod, query.getMethod())
                .ge(begin != null, OperationLog::getCreateTime, begin)
                .le(end != null, OperationLog::getCreateTime, end)
                .orderByDesc(OperationLog::getId);

        Page<OperationLog> page = operationLogMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);

        return PageResult.of(page.convert(OperationLogVO::from));
    }

    @Override
    public void deleteById(Long id) {
        // 日志不可变，物理删除
        operationLogMapper.deleteById(id);
    }

    @Override
    public void clear() {
        operationLogMapper.delete(new LambdaQueryWrapper<>());
    }
}