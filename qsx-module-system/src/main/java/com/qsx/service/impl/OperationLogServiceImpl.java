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

    /** 审计列宽（与 sql/init.sql 的列定义一致，超长会被 MySQL 以 1406 拒绝） */
    private static final int URL_MAX = 255;
    private static final int ERROR_MSG_MAX = 500;

    /** 只做字段搬运；id 由数据库自增、createTime 由 MyBatis-Plus 自动填充 */
    private static OperationLog toEntity(AccessLogCommand command) {
        OperationLog log = new OperationLog();
        log.setUserId(command.getUserId());
        log.setUsername(command.getUsername());
        log.setMethod(command.getMethod());
        log.setUrl(truncate(command.getUrl(), URL_MAX, "url"));
        log.setHttpStatus(command.getHttpStatus());
        log.setSuccess(command.getSuccess());
        log.setErrorMsg(truncate(command.getErrorMsg(), ERROR_MSG_MAX, "error_msg"));
        log.setCostMs(command.getCostMs());
        return log;
    }

    /**
     * 截断到列宽。
     *
     * <p>不截断的后果不是"少记一段文字"，而是**整条审计被丢弃**：MySQL 在 STRICT 模式下
     * 对超长写入直接抛 1406，异常被 {@link #record} 的 catch 吞掉，只剩一行 ERROR 日志——
     * 于是任何人在 URL 后面挂一长串查询参数就能让自己的请求不留审计痕迹
     * （切面写的就是 {@code uri + "?" + queryString} 整串，而查询 DTO 对关键字长度没有约束）。
     * 截断会牺牲尾部信息，但保住了"这次请求发生过"这一审计底线。
     */
    private static String truncate(String value, int max, String field) {
        if (value == null || value.length() <= max) {
            return value;
        }
        log.warn("审计字段超长已截断: field={}, len={}, max={}", field, value.length(), max);
        return value.substring(0, max);
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