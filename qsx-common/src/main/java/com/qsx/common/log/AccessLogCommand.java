package com.qsx.common.log;

import lombok.Data;

/**
 * 审计日志写入指令（跨模块契约）
 *
 * <p>字段与 sys_operation_log 的可写列一一对应，不含 id 与 createTime
 * （分别由数据库自增与 MyBatis-Plus 自动填充）。
 *
 * <p><b>为什么放在 qsx-common</b>：security 的两个异常处理器（401/403）与
 * framework 的切面/全局异常处理器都要构造审计记录，而它们不允许依赖业务模块的
 * OperationLog 实体——否则 common → security → framework 这条单向链会被反向打断。
 *
 * <p>刻意做成可变 POJO 而非 record：调用方（切面）在 try/catch/finally 的不同阶段
 * 逐步回填 success / httpStatus / errorMsg / costMs，用 record 会迫使改造变大。
 */
@Data
public class AccessLogCommand {

    /** 操作人ID（login/register 匿名为 NULL） */
    private Long userId;

    /** 操作人邮箱（冗余便于检索） */
    private String username;

    /** HTTP 方法：GET/POST/PUT/DELETE */
    private String method;

    /** 请求 URL */
    private String url;

    /** HTTP 响应状态码 */
    private Integer httpStatus;

    /** 是否成功：0-失败，1-成功 */
    private Integer success;

    /** 失败原因：业务码/权限/异常消息 */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Integer costMs;
}