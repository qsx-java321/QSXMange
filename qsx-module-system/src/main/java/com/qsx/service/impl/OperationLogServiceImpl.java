package com.qsx.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qsx.common.log.AccessLogCommand;
import com.qsx.common.log.AccessLogRecorder;
import com.qsx.framework.result.PageResult;
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
 *
 * 同时实现写入端口 {@link AccessLogRecorder}：security 的 401/403 处理器与
 * framework 的切面/异常处理器都通过该端口落库，从而不必依赖本模块。
 */
@Slf4j
@Service
public class OperationLogServiceImpl implements OperationLogService, AccessLogRecorder {

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 异步落库一条审计记录（独立线程，失败不影响主请求）
     *
     * <b>@Async 必须标在本方法上，且不得由本类内部自调用</b>：Spring 的异步是基于代理的，
     * 自调用不经过代理会让 @Async 静默失效——日志落库退化为同步、主请求被数据库写入阻塞，
     * 而且不会有任何编译错误。
     */
    @Override
    @Async("operationLogExecutor")
    public void record(AccessLogCommand command) {
        try {
            operationLogMapper.insert(toEntity(command));
        } catch (Exception e) {
            // 异步独立线程：日志落库失败不影响主业务请求，仅记录告警
            log.error("操作日志落库失败: userId={}, url={}, cost={}ms",
                    command.getUserId(), command.getUrl(), command.getCostMs(), e);
        }
    }

    /** 只做字段搬运；id 由数据库自增、createTime 由 MyBatis-Plus 自动填充 */
    private static OperationLog toEntity(AccessLogCommand command) {
        OperationLog log = new OperationLog();
        log.setUserId(command.getUserId());
        log.setUsername(command.getUsername());
        log.setMethod(command.getMethod());
        log.setUrl(command.getUrl());
        log.setHttpStatus(command.getHttpStatus());
        log.setSuccess(command.getSuccess());
        log.setErrorMsg(command.getErrorMsg());
        log.setCostMs(command.getCostMs());
        return log;
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