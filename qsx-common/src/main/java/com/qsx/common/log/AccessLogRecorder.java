package com.qsx.common.log;

/**
 * 审计日志落库端口
 *
 * <p>由业务模块提供实现（本项目为 qsx-module-system 的 OperationLogServiceImpl）。
 *
 * <p><b>实现必须是异步落库</b>：本方法由请求链路同步调用（操作日志切面、401/403
 * 处理器、全局异常处理器），阻塞式落库会拖慢每一个请求。
 *
 * <p><b>复用前置条件</b>：只依赖 qsx-security + qsx-framework 而不引入业务模块的项目，
 * 必须自行提供本接口的实现（可为空实现），否则容器启动会因缺少 Bean 而失败。
 */
public interface AccessLogRecorder {

    /**
     * 落库一条审计记录（异步，失败不影响主请求）
     */
    void record(AccessLogCommand command);
}