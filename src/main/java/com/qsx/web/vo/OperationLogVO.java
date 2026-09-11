package com.qsx.web.vo;

import com.qsx.domain.entity.OperationLog;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志视图对象
 */
@Data
public class OperationLogVO {

    private Long id;
    private Long userId;
    private String username;
    private String method;
    private String url;
    private Integer httpStatus;
    private Integer success;
    private String errorMsg;
    private Integer costMs;
    private LocalDateTime createTime;

    public static OperationLogVO from(OperationLog log) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(log.getId());
        vo.setUserId(log.getUserId());
        vo.setUsername(log.getUsername());
        vo.setMethod(log.getMethod());
        vo.setUrl(log.getUrl());
        vo.setHttpStatus(log.getHttpStatus());
        vo.setSuccess(log.getSuccess());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setCostMs(log.getCostMs());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}