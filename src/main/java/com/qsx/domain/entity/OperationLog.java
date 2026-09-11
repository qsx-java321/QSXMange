package com.qsx.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志实体
 *
 * 审计数据，生而不可变：无逻辑删除与更新时间字段，不做增删改业务，仅插入 + 分页查询。
 */
@Data
@TableName("sys_operation_log")
public class OperationLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

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

    /** 操作时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}