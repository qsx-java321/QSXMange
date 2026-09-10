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

-- =============================================
--  RBAC 权限管理（rbac 分支新增）
--  新增表：sys_role / sys_permission / sys_user_role / sys_role_permission
-- =============================================

-- --------------------------------------------------
-- 角色表
-- code 唯一索引，逻辑删除使用 deleted 标记
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64)  NOT NULL COMMENT '角色编码，如 ADMIN',
    name        VARCHAR(50)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255)          DEFAULT NULL COMMENT '角色描述',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-启用，1-停用',
    create_time DATETIME              DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME              DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统角色表';

-- --------------------------------------------------
-- 权限表（权限码模型，code 即业务权限标识，如 user:add）
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_permission;
CREATE TABLE sys_permission (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64) NOT NULL COMMENT '权限标识，如 user:add',
    name        VARCHAR(50) NOT NULL COMMENT '权限名称',
    type        VARCHAR(20) NOT NULL DEFAULT 'PERMISSION' COMMENT '类型：MENU/PERMISSION',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME             DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME             DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统权限表';

-- --------------------------------------------------
-- 用户-角色关联表（纯关系表，整表替换语义，物理删除，无逻辑删除字段）
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID',
    create_time DATETIME          DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_role_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户-角色关联表';

-- --------------------------------------------------
-- 角色-权限关联表（纯关系表，整表替换语义，物理删除，无逻辑删除字段）
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_role_permission;
CREATE TABLE sys_role_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id       BIGINT   NOT NULL COMMENT '角色ID',
    permission_id BIGINT   NOT NULL COMMENT '权限ID',
    create_time   DATETIME          DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, permission_id),
    KEY idx_role_perm_perm_id (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色-权限关联表';

-- --------------------------------------------------
-- 预置数据（幂等）
-- 插入顺序：权限 → 角色 → 角色-权限 → 用户 → 用户-角色
-- --------------------------------------------------
-- 1. 全量权限码（与 PermissionConstants 保持一致）
INSERT IGNORE INTO sys_permission (code, name, type, sort, deleted) VALUES
('user:page',       '用户分页',     'PERMISSION', 1, 0),
('user:get',        '用户详情',     'PERMISSION', 2, 0),
('user:create',     '新增用户',     'PERMISSION', 3, 0),
('user:update',     '修改用户',     'PERMISSION', 4, 0),
('user:delete',     '删除用户',     'PERMISSION', 5, 0),
('user:assign-role','分配用户角色', 'PERMISSION', 6, 0),
('role:page',       '角色分页',     'PERMISSION', 7, 0),
('role:get',        '角色详情',     'PERMISSION', 8, 0),
('role:create',     '新增角色',     'PERMISSION', 9, 0),
('role:update',     '修改角色',     'PERMISSION', 10, 0),
('role:delete',     '删除角色',     'PERMISSION', 11, 0),
('role:assign',     '分配角色权限', 'PERMISSION', 12, 0),
('perm:page',       '权限分页',     'PERMISSION', 13, 0),
('perm:get',        '权限详情',     'PERMISSION', 14, 0);

-- 2. 超级管理员角色
INSERT IGNORE INTO sys_role (code, name, description, status, deleted)
VALUES ('ADMIN', '超级管理员', '系统内置超管，绑定全部权限', 0, 0);

-- 3. 超级管理员绑定全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'ADMIN'), id FROM sys_permission;

-- 4. 超级管理员账号（默认密码 admin123，可用 BCryptPasswordEncoder 对目标密码重新生成 hash 后替换）
INSERT IGNORE INTO sys_user (email, password, nickname, status, deleted)
VALUES ('admin@qsx.com', '$2a$10$3KuSUz6n6SzyMXi535r3Su/qP6AaVWdCvjNUx0FWVFj4DS4tca3By', '超级管理员', 0, 0);

-- 5. 超级管理员绑定 ADMIN 角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT (SELECT id FROM sys_user WHERE email = 'admin@qsx.com'),
       (SELECT id FROM sys_role WHERE code = 'ADMIN');