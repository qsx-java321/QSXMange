package com.qsx.web.dto.query;

import lombok.Data;

/**
 * 用户分页查询条件
 */
@Data
public class UserQuery {

    private String email;
    private String nickname;
    /** 状态：0-正常，1-禁用，null则全部 */
    private Integer status;

    private Integer pageNum = 1;
    private Integer pageSize = 10;
}