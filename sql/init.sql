-- =============================================
--  QSX 后台管理系统 - 基础版建表脚本
--  数据库: QSXManager
-- =============================================

USE QSXManager;

-- --------------------------------------------------
-- 用户表
-- 说明：
--   1. email 唯一索引仅对“未被逻辑删除”的记录生效，
--      以支持逻辑删除后通过拼接时间戳后缀释放邮箱。
--   2. deleted 逻辑删除标记：0-正常，1-已删除
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    email       VARCHAR(128) NOT NULL COMMENT '邮箱（登录账号）',
    password    VARCHAR(128) NOT NULL COMMENT '密码（BCrypt加密）',
    nickname    VARCHAR(50)           DEFAULT NULL COMMENT '昵称',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-正常，1-禁用',
    create_time DATETIME              DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME              DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

-- 可选：预置一个管理员账号（密码请用注册接口或手动 BCrypt 生成后替换）
-- INSERT INTO sys_user (email, password, nickname, status, deleted)
-- VALUES ('admin@qsx.com', '$2a$10$此处为BCrypt哈希', '管理员', 0, 0);