package com.qsx.web.dto.query;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作日志分页查询条件
 */
@Data
public class LogQuery {

    /** 操作人邮箱（模糊） */
    private String username;

    /** 请求 URL（模糊） */
    private String url;

    /** 是否成功：0-失败，1-成功，null 则全部 */
    private Integer success;

    /** HTTP 方法类型，如 GET/POST */
    private String method;

    /** 起始时间（起） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime beginTime;

    /** 起始时间（止） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
}