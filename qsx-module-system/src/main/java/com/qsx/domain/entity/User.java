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

    /**
     * 是否必须先改密：true 时该账号除 {@code /auth/**} 之外的接口一律被拒（业务码 1037）。
     *
     * <p>置 true 的三处：init.sql 预置超管、Excel 导入落库、后台建号（管理员设定的口令
     * 对管理员是已知的）；改密与忘记密码重置成功后置 false，唯一写库入口是
     * {@code UserServiceImpl#updatePassword}。
     *
     * <p>用包装类型是**必须的**：MyBatis-Plus 默认 NOT_NULL 字段策略下，只有非 null 的值
     * 才会进 UPDATE 的 SET 子句——写成基本类型就无法表达"置 false"，标志永远清不掉。
     */
    private Boolean mustChangePassword;
}