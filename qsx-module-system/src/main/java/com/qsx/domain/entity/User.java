package com.qsx.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.qsx.domain.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统用户表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class User extends BaseEntity {

    /** 邮箱（登录账号，需唯一） */
    private String email;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 状态：0-正常，1-禁用 */
    private Integer status;
}