# QSXManager 全项目主线 - 总总结

> 类型：全项目总总结（稳定文件名，随里程碑更新）｜ 最近更新：2026-09-13
> **读者导航**：想快速了解项目全貌、当前状态、跨阶段决策与共性坑 → 读本文件；想查某阶段的实施细节 / 测试报告 → 读下方「阶段导航」指向的对应增量文件（增量保留作历史快照，不删除）。

## 〇、项目现状快照（截至 2026-09-13）

- **定位**：单体单模块、前后端分离中小型后台管理系统（Spring Boot 3.5.16 / Java 21 / MyBatis-Plus / MySQL 8.4 / Redis / jjwt），根包 `com.qsx`，分支 `master`
- **已完成六大模块**：认证中心（双令牌会话）→ RBAC 权限（权限码 + 菜单树复用一表）→ 操作日志（AOP 审计）→ Excel 批量导入导出 → 权限缓存（Redis）→ 会话管理（登出 / 强制登出 / 禁用解冻保护）
- **工程记忆红线**（项目 memory / wrap 技能固化，本文件不重复抄写）：权限表只读、ADMIN 禁删、整表替换授权、逻辑删除释放邮箱、AFTER_COMMIT 失效缓存、fail-open 权限缓存 / fail-closed 会话、测试双轨制
- **最新提交**：`cd73263`（会话管理 feat）、`ca9a0a2`（会话总结 docs）；自动化用例 **85/85** 全绿

## 一、发展阶段脉络（时间线主线）

### 阶段 1 · 基础版（09-10）：工程 + 认证中心 + 用户管理
从 0 搭建（统一 Result/异常、JWT 认证、用户 CRUD），确立「逻辑删除释放邮箱」「唯一邮箱即账号」等基石。35 用例全过。
→ 细节见 `会话总结-qsxmanager-20260910.md`

### 阶段 2 · RBAC 权限管理（09-10）
新增 4 表成标准 RBAC 5 表；确立**权限码模型** + `@PreAuthorize` 方法级鉴权 + 每请求查库即时生效；`ADMIN` 角色绑定全量权限（非硬编码）；内置超管禁删。40 用例全过。
→ 细节见 `会话总结-qsxmanager-rbac-20260910.md`

### 阶段 3 · 菜单管理（09-11）：动态路由菜单树
**复用 `sys_permission` 表承载菜单**（type=MENU/PERMISSION，parent_id 成树），`/api/menus/current` 按权限过滤 + 补祖先链供前端动态路由；防环、有子禁删。48 用例全过。
→ 细节见 `会话总结-qsxmanager-menu-20260911.md`

### 阶段 4 · 操作日志（09-11）：AOP 访问审计
包级切面（非注解）全量审计 + `@Async` 异步落库 + 401/403/400 三处补记；日志表不可变。15 个真实穿透场景验证。
→ 细节见 `会话总结-qsxmanager-log-20260911.md` + `测试报告-qsxmanager-log-20260911.md`

### 阶段 5 · Excel 批量导入导出（09-12）
EasyExcel 流式（文件不落盘）、整批校验整体拒绝、模板含角色列、默认密码配置化；同期 `docs/` 目录整理（`.trae/` gitignore、session-notes 按类别分文件夹）。63 用例全过。
→ 细节见 `会话总结-qsxmanager-excel-20260912.md` + `测试报告-qsxmanager-excel-20260912.md`

### 阶段 6 · RBAC 权限缓存（09-12，Redis）
三处读取点收口 `PermissionCacheService.load`（Redis 优先 + 回源回填 + 空权限防穿透 + enabled 开关）；`AFTER_COMMIT` 事件精确失效；同步补事务注解修复整表替换原子性问题。66 用例 + 真实 HTTP 38 调用全过。
→ 细节见 `会话总结-qsxmanager-redis-20260912.md` + `docs/test/test-report.md`（该阶段实施计划已随完成归档删除）

### 阶段 7 · 会话管理（09-13，refresh token）：登出 / 强踢 / 禁用解冻
**双令牌会话**：access 30min + refresh（Redis 哈希、单端、滑动 7d + 30d 上限、轮换语义、fail-closed）。新增 `/auth/refresh`、`/auth/logout`、`/api/users/{id}/kick`（user:kick）。**禁用=status 赋值**（自带踢下线），补禁用自己/超管保护。85 用例全过 + 真实 HTTP 全链路。
→ 细节见 `会话总结-qsxmanager-refresh-20260913.md`

## 二、跨阶段关键决策与演进（横切视角）

- **数据模型演进**：`sys_user` 单表 → RBAC 5 表（关系表物理删除）→ `sys_permission` 一表两用（菜单+按钮权限）→ 日志表不可变
- **认证演进**：单 JWT 24h 无状态 → 权限每请求查库 → 权限 Redis 缓存（fail-open 降级）→ 双令牌会话（会话层 fail-closed）
- **鉴权模式（贯穿）**：权限码 + `@PreAuthorize`，ADMIN 绑定全量权限——新增权限须同步 init.sql 预置（INSERT IGNORE + 自动绑 ADMIN）
- **「变更即时生效」原则**：权限靠每请求加载 + AFTER_COMMIT 失效；禁用靠每请求查库 status；因此权限变更不需要踢人
- **测试体系演进**：35 → 40 → 48 → 63 → 66 → 85 用例；MockMvc + 真实 MySQL/Redis 集成测试 + 真实 HTTP 双轨制；`adminToken()` 走真实 assignRoles 链路

## 三、跨阶段共性教训（高频坑沉淀）

- **测试基类 `userMapper.delete(null)` 全表逻辑删除**（MyBatis-Plus `@TableLogic`）：污染预置 admin、用例隔离失效——09-12 发现并恢复数据、09-13 改为 JdbcTemplate 物理删除根治
- **PowerShell 三大坑**：内嵌 JSON 用反引号转义引号（勿用 `\"`）；多级属性插值先赋标量变量；多行 commit 用多个 `-m`（heredoc 不可用）、`-Dtest=A,B` 加引号
- **MockMvc 断言陷阱**：业务失败是 HTTP 200 + 业务码 ≠ 200，`andExpect(isOk())` 拦不住，必须断言业务码
- **预置超管易被测试污染**：真实黑盒验证前先确认 `admin@qsx.com` 未被逻辑删除 / 关联未清
- **docker exec 传 SQL**：避免嵌套引号地狱，用管道或 `docker exec mysql mysql` 直连方式

## 四、遗留事项（截至 09-13）

- [ ] access token 黑名单（禁用/踢人即时中断，当前靠实时查库 status + 30min 令牌窗口收敛）
- [ ] 邮箱验证码注册
- [ ] 前端管理界面（对接菜单树动态路由）
- [ ] 后续里程碑完成后更新本总总结（追加阶段 + 刷新头部）