-- =============================================
--  QSX 后台管理系统 - 基础版建表脚本
--  数据库: QSXManager
-- =============================================
-- 使用说明（破坏性警告）：
--   本脚本为【首次初始化 / 全量重建】脚本，会 DROP 并重建全部 6 张表，
--   执行将清空数据库中的所有数据，已有环境请勿直接重跑！
--   初始化方式（二选一）：
--     ① mysql 客户端：
--        mysql --default-character-set=utf8mb4 -uroot -p123456 < sql/init.sql
--     ② docker（推荐）：
--        docker cp sql/init.sql mysql:/tmp/init.sql
--        docker exec mysql sh -c "mysql --default-character-set=utf8mb4 -uroot -p123456 < /tmp/init.sql"
--   注：上面命令行里的 -p123456 是**本地 Docker 开发库**的口令（见 docs/dev-env），
--       生产形态的连接口令走环境变量 DB_PASSWORD，不写在这里。
--   脚本自包含建库（CREATE DATABASE IF NOT EXISTS）并声明会话字符集（SET NAMES utf8mb4），
--   与客户端 --default-character-set=utf8mb4 形成双保险，防止中文乱码。
-- 变更历史：
--   v1.0  RBAC 权限管理：sys_role / sys_permission / sys_user_role / sys_role_permission + 预置数据
--   v1.5  操作日志：sys_operation_log + 日志菜单/按钮权限
--   v1.6  Excel 批量导入导出权限：user:import / user:export
--   v1.7  会话管理（refresh token）：user:kick 强制登出权限
--   v1.8  强制首次改密：sys_user.must_change_password（预置超管种子置 1）
--
-- 已有环境升级（本脚本是 DROP 重建，不能对既有库重跑）：
--   ALTER TABLE sys_user
--     ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0
--     COMMENT '必须修改密码：0-否，1-是（为1时除 /auth/** 外一律拒绝，业务码 1037）'
--     AFTER status;
--   UPDATE sys_user SET must_change_password = 1 WHERE email = 'admin@qsx.com';
-- =============================================

-- 建库（幂等，统一 utf8mb4 字符集与排序规则）
CREATE DATABASE IF NOT EXISTS QSXManager DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE QSXManager;
-- 声明会话字符集（与客户端 --default-character-set=utf8mb4 双保险）
SET NAMES utf8mb4;

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
    must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '必须修改密码：0-否，1-是（为1时除 /auth/** 外一律拒绝，业务码 1037）',
    create_time DATETIME              DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME              DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统用户表';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统角色表';

-- --------------------------------------------------
-- 权限表（权限码模型，code 即业务权限标识，如 user:add）
-- 菜单（type=MENU）与按钮权限（type=PERMISSION）共用本表：
--   菜单通过 parent_id 构成树；按钮权限作为叶子挂在所属菜单下
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_permission;
CREATE TABLE sys_permission (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64) NOT NULL COMMENT '权限标识（菜单/按钮，如 system-user / user:add），需唯一',
    name        VARCHAR(50) NOT NULL COMMENT '名称（菜单名或权限名）',
    type        VARCHAR(20) NOT NULL DEFAULT 'PERMISSION' COMMENT '类型：MENU-菜单 / PERMISSION-按钮权限',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父ID：0-顶级；按钮权限指向所属菜单ID',
    path        VARCHAR(200)         DEFAULT NULL COMMENT '菜单路由地址',
    component   VARCHAR(200)         DEFAULT NULL COMMENT '前端组件路径',
    icon        VARCHAR(50)          DEFAULT NULL COMMENT '菜单图标',
    visible     TINYINT     NOT NULL DEFAULT 1 COMMENT '是否显示：1-显示，0-隐藏',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序（同级内升序）',
    create_time DATETIME             DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME             DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统权限表（菜单+按钮权限）';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户-角色关联表';

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '角色-权限关联表';

-- --------------------------------------------------
-- 操作日志表（日志模块新增）
-- 说明：日志为审计数据，生而不可变，无 deleted/update_time 字段
-- --------------------------------------------------
DROP TABLE IF EXISTS sys_operation_log;
CREATE TABLE sys_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT                DEFAULT NULL COMMENT '操作人ID（login/register 匿名为NULL）',
    username    VARCHAR(128)          DEFAULT NULL COMMENT '操作人邮箱（冗余便于检索）',
    method      VARCHAR(10)  NOT NULL COMMENT 'HTTP方法：GET/POST/PUT/DELETE',
    url         VARCHAR(255) NOT NULL COMMENT '请求URL',
    http_status INT          NOT NULL COMMENT 'HTTP响应状态码',
    success     TINYINT      NOT NULL COMMENT '是否成功：0-失败，1-成功',
    error_msg   VARCHAR(500)          DEFAULT NULL COMMENT '失败原因：业务码/权限/异常消息',
    cost_ms     INT          NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    create_time DATETIME              DEFAULT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_log_user (user_id),
    KEY idx_log_success (success),
    KEY idx_log_create (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统操作日志表';

-- --------------------------------------------------
-- 预置数据（幂等）
-- 插入顺序：权限 → 角色 → 角色-权限 → 用户 → 用户-角色
-- --------------------------------------------------
-- 1. 菜单（type=MENU）与按钮权限（type=PERMISSION）全量预置（与 PermissionConstants 保持一致）
--    插入顺序：先菜单后按钮权限（按钮权限的 parent_id 稍后统一 UPDATE）
INSERT IGNORE INTO sys_permission (code, name, type, parent_id, path, component, icon, visible, sort, deleted) VALUES
('system',         '系统管理', 'MENU', 0, '/system', 'Layout',        'setting', 1, 1, 0),
('system-user',    '用户管理', 'MENU', 0, 'user',    'system/user',   'user',    1, 1, 0),
('system-role',    '角色管理', 'MENU', 0, 'role',    'system/role',   'peoples', 1, 2, 0),
('system-perm',    '权限管理', 'MENU', 0, 'perm',    'system/perm',   'tree',    1, 3, 0),
('system-menu',    '菜单管理', 'MENU', 0, 'menu',    'system/menu',   'menu',    1, 4, 0);

INSERT IGNORE INTO sys_permission (code, name, type, parent_id, sort, deleted) VALUES
('user:page',        '用户分页',     'PERMISSION', 0, 1, 0),
('user:get',         '用户详情',     'PERMISSION', 0, 2, 0),
('user:create',      '新增用户',     'PERMISSION', 0, 3, 0),
('user:update',      '修改用户',     'PERMISSION', 0, 4, 0),
('user:delete',      '删除用户',     'PERMISSION', 0, 5, 0),
('user:assign-role', '分配用户角色', 'PERMISSION', 0, 6, 0),
('role:page',        '角色分页',     'PERMISSION', 0, 7, 0),
('role:get',         '角色详情',     'PERMISSION', 0, 8, 0),
('role:create',      '新增角色',     'PERMISSION', 0, 9, 0),
('role:update',      '修改角色',     'PERMISSION', 0, 10, 0),
('role:delete',      '删除角色',     'PERMISSION', 0, 11, 0),
('role:assign',      '分配角色权限', 'PERMISSION', 0, 12, 0),
('perm:page',        '权限分页',     'PERMISSION', 0, 13, 0),
('perm:get',         '权限详情',     'PERMISSION', 0, 14, 0),
('menu:tree',        '菜单树',       'PERMISSION', 0, 15, 0),
('menu:create',      '新增菜单',     'PERMISSION', 0, 16, 0),
('menu:update',      '修改菜单',     'PERMISSION', 0, 17, 0),
('menu:delete',      '删除菜单',     'PERMISSION', 0, 18, 0);

-- 2. 挂载归属：子菜单挂到顶级菜单下，按钮权限挂到所属菜单下
UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system'
SET p.parent_id = parent.id
WHERE p.code IN ('system-user', 'system-role', 'system-perm', 'system-menu');

UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-user'
SET p.parent_id = parent.id
WHERE p.code LIKE 'user:%';

UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-role'
SET p.parent_id = parent.id
WHERE p.code LIKE 'role:%';

UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-perm'
SET p.parent_id = parent.id
WHERE p.code LIKE 'perm:%';

UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-menu'
SET p.parent_id = parent.id
WHERE p.code LIKE 'menu:%';

-- --------------------------------------------------
-- 1.5 日志管理菜单与按钮权限（日志模块新增，挂载点为 system 系统管理）
-- --------------------------------------------------
INSERT IGNORE INTO sys_permission (code, name, type, parent_id, path, component, icon, visible, sort, deleted) VALUES
('system-log', '日志管理', 'MENU', 0, 'log', 'system/log', 'documentation', 1, 5, 0);

INSERT IGNORE INTO sys_permission (code, name, type, parent_id, sort, deleted) VALUES
('log:page',   '日志分页', 'PERMISSION', 0, 19, 0),
('log:delete', '删除日志', 'PERMISSION', 0, 20, 0);

-- 挂载归属：日志菜单挂到顶级，日志按钮权限挂到日志菜单下
UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system'
SET p.parent_id = parent.id
WHERE p.code = 'system-log';

UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-log'
SET p.parent_id = parent.id
WHERE p.code LIKE 'log:%';

-- --------------------------------------------------
-- 1.6 Excel 批量导入导出权限（用户管理模块新增）
-- --------------------------------------------------
INSERT IGNORE INTO sys_permission (code, name, type, parent_id, sort, deleted) VALUES
('user:import', '批量导入用户', 'PERMISSION', 0, 21, 0),
('user:export', '批量导出用户', 'PERMISSION', 0, 22, 0);

-- 挂载归属：导入导出按钮权限挂到用户管理菜单下
UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-user'
SET p.parent_id = parent.id
WHERE p.code IN ('user:import', 'user:export');

-- --------------------------------------------------
-- 1.7 强制登出（踢下线）权限（会话管理新增）
-- --------------------------------------------------
INSERT IGNORE INTO sys_permission (code, name, type, parent_id, sort, deleted) VALUES
('user:kick', '强制登出用户', 'PERMISSION', 0, 23, 0);

-- 挂载归属：强制登出按钮权限挂到用户管理菜单下
UPDATE sys_permission p
JOIN sys_permission parent ON parent.code = 'system-user'
SET p.parent_id = parent.id
WHERE p.code = 'user:kick';

-- 3. 超级管理员角色
INSERT IGNORE INTO sys_role (code, name, description, status, deleted)
VALUES ('ADMIN', '超级管理员', '系统内置超管，绑定全部权限', 0, 0);

-- 4. 超级管理员绑定全部权限（仅绑定未逻辑删除的权限）
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'ADMIN'), id FROM sys_permission WHERE deleted = 0;

-- 5. 超级管理员账号
--    口令是本地开发用的公开初始口令 admin123（hash 由 BCryptPasswordEncoder 生成）。
--    must_change_password = 1：首次登录后**必须改密**才能访问 /auth/** 以外的接口
--    （业务码 1037），改密成功即自动置 0 —— 这条是「公开口令」真正被收口的地方。
--    ⚠️ 测试基类会在造数前把本行复位成「此处的 hash + 标志 0」，所以本地若手工改过
--       超管口令，跑一次 `mvn test` 就会把它还原成 admin123。
--    注意列清单里**必须有** must_change_password：漏写会取 DB 默认 0，
--    而 INSERT IGNORE 对「行已存在」又直接跳过，两种情形都不报错。
INSERT IGNORE INTO sys_user (email, password, nickname, status, must_change_password, deleted)
VALUES ('admin@qsx.com', '$2a$10$3KuSUz6n6SzyMXi535r3Su/qP6AaVWdCvjNUx0FWVFj4DS4tca3By', '超级管理员', 0, 1, 0);

-- 6. 超级管理员绑定 ADMIN 角色
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT (SELECT id FROM sys_user WHERE email = 'admin@qsx.com'),
       (SELECT id FROM sys_role WHERE code = 'ADMIN');