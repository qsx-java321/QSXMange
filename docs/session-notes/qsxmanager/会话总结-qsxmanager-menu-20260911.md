# QSXManager - 菜单管理（会话总结）

> 总结时间：2026-09-11 ｜ 来源：当前会话（菜单管理功能开发）

## 〇、本次状态变化
- **新增文件**：
  - `src/main/java/com/qsx/web/controller/admin/menu/MenuController.java`（/api/menus 5 接口）
  - `src/main/java/com/qsx/web/dto/request/MenuCreateRequest.java`、`MenuUpdateRequest.java`
  - `src/test/java/com/qsx/MenuTest.java`（8 个用例）
  - `docs/session-notes/qsxmanager/会话总结-qsxmanager-menu-20260911.md`（本文件）
- **修改文件**：`sql/init.sql`（sys_permission 加菜单字段 + 5 菜单预置 + menu:* 权限码 + 归属挂载）、`Permission.java`/`PermissionVO.java`（菜单字段 + children 树形）、`PermissionConstants.java`（menu:* + 类型常量）、`ResultCode.java`（1013~1016）、`PermissionService.java`/`Impl`（tree/create/update/delete/getUserMenuTree）、`README.md`（菜单管理章节）
- **完成度变化**：认证中心 + 用户管理 + RBAC + **菜单管理** 后端闭环就绪；自动化用例 40 → **48 全通过**（MenuTest 8 个新增）
- **遗留事项变化**：README 原停留在基础版 → 已更新到含菜单管理；数据库已复位（1 超管 / 5 菜单 / 18 按钮权限）；待办：Redis 权限缓存、邮箱验证码、日志审计、前端界面

## 一、基础上下文
- **项目 / 主题**：QSXManager 后台管理系统 - 菜单管理（动态路由菜单树）
- **时间范围**：2026-09-11
- **涉及范围（文件 / 模块 / 分支）**：`com.qsx` 的 web/service/mapper/domain/common 四层 + `sql/init.sql` + README + 测试；master 分支

## 二、已完成任务
- 更新 README 到 RBAC 阶段（承接上次 RBAC 开发收尾）
- 讲解 Docker MySQL 数据/设计（mysql:8.4 容器 running、数据卷 mysql-data、QSXManager 库 5 表现状）与 `init.sql` 用法（幂等预置 + DROP 重建语义）
- 实测验证内置超管 `admin@qsx.com/admin123`：登录 200 + 全部权限码，增删改查、逻辑删除释放邮箱、普通用户 403 全部通过
- 讨论并确定菜单管理方案（复用 sys_permission 表 + 完整闭环）
- **实现菜单管理**：
  - `sys_permission` 新增 `parent_id/path/component/icon/visible`，预置 5 个菜单（系统管理→用户/角色/权限/菜单管理）、新增 `menu:tree/create/update/delete` 4 个权限码（共 18 个按钮权限），按钮权限归属挂载到对应菜单，ADMIN 绑定全量（23 条）
  - `GET /api/menus/tree`（menu:tree 全量树）、`GET /api/menus/current`（登录可见，按权限过滤 + 补祖先，供前端动态路由）、`POST/PUT/DELETE /api/menus`
  - 约束：有子节点禁删（1014）、父节点防环（1015）、标识唯一（1016）、删除时清理 role_permission 关联
- **验证**：启动实测全链路 + `mvn test` 全量 48 用例通过；数据库复位

## 三、错误与教训
- **做错了什么**：
  - 技术坑：环境无 Python bcrypt 模块，改用"启动应用实测登录"验证哈希有效性
  - 流程失误：实测删除约束时，PowerShell 双引号内用 `\"` 转义 JSON 导致请求体破损、子节点创建失败，且被 `Out-Null` 吞掉返回，一度误判为"删除约束失效"；改用反引号转义后功能正常
- **吸取的教训**：PowerShell 内嵌 JSON 用反引号转义引号，不要用 `\"`；联调时不要用 `Out-Null` 吞掉响应，先看返回再断言

## 四、关键技术抉择
- **菜单数据复用 `sys_permission` 表**（放弃独立 sys_menu + 菜单角色关联表）：type 字段已预留 MENU，菜单即权限，现有 role-permission 关联、@PreAuthorize、每请求加载权限链路零改动，改动最小
- **范围做完整闭环**：含 `current` 按用户权限过滤的菜单树，前端拿到即可动态生成路由
- **权限过滤策略**：按用户权限码命中菜单后**补齐祖先链**，保证有子菜单权限时父菜单入口可见
- **删除策略**：有子节点禁止删除（防孤立子树）+ 删除时物理清理角色-权限关联
- **current 接口权限**：仅要求登录（authenticated），不额外权限码，前端首屏即可用
