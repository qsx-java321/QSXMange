-- QSXManager 数据修复脚本（一次性）：
-- 1) 修复预置数据中文乱码（历史导入未按 utf8mb4，UTF-8 字节被误转成 mojibake）
-- 2) 清理历史自动化测试残留数据（测试用户/角色/菜单）

USE QSXManager;

-- 1. 修复预置中文（对照 init.sql 字面量）
UPDATE sys_user SET nickname = '超级管理员' WHERE email = 'admin@qsx.com';

UPDATE sys_role SET name = '超级管理员', description = '系统内置超管，绑定全部权限' WHERE code = 'ADMIN';

UPDATE sys_permission SET name = CASE code
    WHEN 'system'         THEN '系统管理'
    WHEN 'system-user'    THEN '用户管理'
    WHEN 'system-role'    THEN '角色管理'
    WHEN 'system-perm'    THEN '权限管理'
    WHEN 'system-menu'    THEN '菜单管理'
    WHEN 'system-log'     THEN '日志管理'
    WHEN 'user:page'      THEN '用户分页'
    WHEN 'user:get'       THEN '用户详情'
    WHEN 'user:create'    THEN '新增用户'
    WHEN 'user:update'    THEN '修改用户'
    WHEN 'user:delete'    THEN '删除用户'
    WHEN 'user:assign-role' THEN '分配用户角色'
    WHEN 'role:page'      THEN '角色分页'
    WHEN 'role:get'       THEN '角色详情'
    WHEN 'role:create'    THEN '新增角色'
    WHEN 'role:update'    THEN '修改角色'
    WHEN 'role:delete'    THEN '删除角色'
    WHEN 'role:assign'    THEN '分配角色权限'
    WHEN 'perm:page'      THEN '权限分页'
    WHEN 'perm:get'       THEN '权限详情'
    WHEN 'menu:tree'      THEN '菜单树'
    WHEN 'menu:create'    THEN '新增菜单'
    WHEN 'menu:update'    THEN '修改菜单'
    WHEN 'menu:delete'    THEN '删除菜单'
    WHEN 'log:page'       THEN '日志分页'
    WHEN 'log:delete'     THEN '删除日志'
    WHEN 'user:import'    THEN '批量导入用户'
    WHEN 'user:export'    THEN '批量导出用户'
    ELSE name
END
WHERE code IN ('system','system-user','system-role','system-perm','system-menu','system-log',
               'user:page','user:get','user:create','user:update','user:delete','user:assign-role',
               'role:page','role:get','role:create','role:update','role:delete','role:assign',
               'perm:page','perm:get','menu:tree','menu:create','menu:update','menu:delete',
               'log:page','log:delete','user:import','user:export');

-- 2. 清理测试残留（先关系表，后主表，物理删除）
DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE email LIKE '%@test.com');
DELETE FROM sys_user      WHERE email LIKE '%@test.com';

DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE code LIKE 'test-role%' OR code LIKE 'ROLE_%' OR code LIKE 'TEST_ROLE%');
DELETE FROM sys_user_role       WHERE role_id IN (SELECT id FROM sys_role WHERE code LIKE 'test-role%' OR code LIKE 'ROLE_%' OR code LIKE 'TEST_ROLE%');
DELETE FROM sys_role            WHERE code LIKE 'test-role%' OR code LIKE 'ROLE_%' OR code LIKE 'TEST_ROLE%';

DELETE FROM sys_role_permission WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'test-menu%');
DELETE FROM sys_permission      WHERE code LIKE 'test-menu%';

-- 3. 校验
SELECT id, email, nickname, deleted FROM sys_user;
SELECT id, code, name FROM sys_role;
SELECT COUNT(*) AS permission_count FROM sys_permission WHERE deleted = 0;
