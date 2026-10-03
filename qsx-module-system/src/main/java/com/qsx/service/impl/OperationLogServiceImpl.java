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
 * 操作日志服务实现（异步写入端口 + 查询/删除/清空）。
 *
 * <p>业务职责：审计日志的异步落库与分页查询、按 ID 删除、一键清空。
 *
 * <p>使用场景：security 的 401/403 处理器与 framework 的切面/异常处理器通过
 * {@link AccessLogRecorder} 端口异步写入；日志管理端接口经 {@link OperationLogService}
 * 查询与维护（{@code /api/logs} 前缀被审计切面整体排除，这些操作自身不记审计）。
 *
 * <p>核心依赖：{@link OperationLogMapper}（持久层）、{@code operationLogExecutor}
 * 线程池（{@link Async}）。
 *
 * <p>线程安全与失败策略：写入在独立线程池异步执行，落库失败仅 ERROR 告警、绝不影响
 * 主请求；超长字段先截断再落库，避免 MySQL 1406 使整条审计被静默丢弃。
 */
@Slf4j
@Service
public class OperationLogServiceImpl implements OperationLogService, AccessLogRecorder {

    private final OperationLogMapper operationLogMapper;

    public OperationLogServiceImpl(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 异步落库一条审计记录（独立线程，失败不影响主请求）。
     *
     * <p><b>@Async 必须标在本方法上，且不得由本类内部自调用</b>：Spring 的异步是基于代理的，
     * 自调用不经过代理会让 @Async 静默失效——日志落库退化为同步、主请求被数据库写入阻塞，
     * 而且不会有任何编译错误。url/errorMsg 先截断再落库，避免列宽溢出（1406）导致整条审计
     * 被丢弃、使请求不留痕迹。
     *
     * @param command 审计命令（用户、方法、URL、HTTP 状态、成功标志、错误信息、耗时等），
     *                由 {@link AccessLogRecorder} 各调用点按响应语义填充
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

    /**
     * 分页查询操作日志。
     *
     * <p>过滤条件均可空：用户名/URL 模糊匹配，成功标志/方法精确匹配，时间范围取闭区间；
     * 按 id 倒序（最新的在前）。
     *
     * @param query 分页查询条件（页码、页大小、可选的用户名/URL/成功标志/方法/起止时间）
     * @return 分页结果，每行为日志视图对象
     */
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

    /**
     * 按主键删除单条日志（日志不可变，此处物理删除）。
     *
     * @param id 日志 ID；记录不存在时为无操作（幂等，不报错）
     */
    @Override
    public void deleteById(Long id) {
        // 日志不可变，物理删除
        operationLogMapper.deleteById(id);
    }

    /**
     * 清空全部操作日志（物理删除，不可恢复）。
     */
    @Override
    public void clear() {
        operationLogMapper.delete(new LambdaQueryWrapper<>());
    }
}