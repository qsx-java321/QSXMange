# QSXManager RBAC 权限管理 - 会话总结

> 总结时间：2026-09-10 ｜ 来源：当前会话
> 读者导航：想速览本次 RBAC 迭代读本文；想回顾基础版（认证+用户管理）读同目录 `会话总结-qsxmanager-20260910.md`。

## 〇、本次状态变化
- **分支**：从 `master` 新建 `rbac` → 4 次功能提交 → **Fast-forward 合并回 `master`** → 删除 `rbac` 分支；`master` 已推送 `origin`（`68f84bd..1e9c9ca`）。当前仅剩本地 `master`，与远端同步。
- **新增数据表 4 张**（`sys_role/sys_permission/sys_user_role/sys_role_permission`）+ 原有 `sys_user` = 标准 RBAC 5 表。
- **新增功能**：完整 RBAC（权限码模型 + `@PreAuthorize` 方法级鉴权 + 每请求查库即时生效），14 个权限码、预置 ADMIN 超管角色与 admin 账号。
- **新增文件**：`PermissionConstants`、`Role/Permission/UserRole/RolePermission` 实体与 4 个 Mapper、`RoleService/PermissionService(+impl)`、`RoleController/PermissionController`、角色/权限 DTO 与 VO、`RbacTest`；`.trae/documents/` 规划文档与测试报告；`docs/session-notes/` 本篇。
- **修改文件**：`init.sql`、`SecurityUser`、`SecurityUserDetailsService`、`UserMapper`、`UserController`、`UserService(+impl)`、`AuthServiceImpl`、`LoginVO`、`GlobalExceptionHandler`、`ResultCode`；测试基类与既有 4 个测试类。
- **遗留事项变化**：基础版遗留项（Redis 权限缓存、邮箱验证码、日志）仍未做，RBAC 迭代未引入 Redis（刻意）。

## 一、基础上下文
- **项目 / 主题**：QSXManager 后台管理系统 —— RBAC 权限管理迭代
- **时间范围**：2026-09-10
- **涉及范围**：`com.qsx` 根包（security/mapper/service/web/domain/common）、`sql/init.sql`、测试、分支 `rbac`（已并入 `master`）

## 二、已完成任务
- **完整 RBAC 实现**（提交：`f0c8fff`、`2519a7d`、`c57f898`）：
  - 5 表 + 种子数据（14 权限码、ADMIN 角色全量绑定、`admin@qsx.com/admin123` 账号，真实 BCrypt 哈希）
  - 权限加载链路：`SecurityUserDetailsService` 每请求查角色码/权限码，`SecurityUser.getAuthorities()` 返回 `ROLE_` 前缀角色 + 权限码，JWT 过滤器零改动
  - 接口：`/api/roles`（CRUD+授权/清权）、`/api/permissions`（只读）、`PUT /api/users/{id}/roles`；用户接口挂 `@PreAuthorize`，权限码集中于 `PermissionConstants`
  - `GlobalExceptionHandler` 处理 `AccessDeniedException`，方法级拒权返回 403 JSON
- **测试改造与补充**（`88a1bfd`）：`BaseIntegrationTest` 新增 `adminToken()`（注册+绑 ADMIN）；新增 `RbacTest` 5 用例（超管/普通 403/细粒度/即时生效/分配幂等）；调整既有 4 测试类改用超管 token
- **规划文档入库**（`1e9c9ca`）：`.trae/documents/rbac-permission-management-plan.md`
- **完整测试**：自动化 `mvn test` **40/40 通过**；真实网络 HTTP（app 于 8080）15 组流程全过（登录/me/权限/用户/注册/403/401/细粒度/即时生效/改密/删资源/内置超管保护）
- **测试报告**：`.trae/documents/测试报告-qsxmanager-rbac-20260910.md`
- **收尾**：停止应用、数据库重置为纯净种子态（仅 admin，无测试残留）

## 三、错误与教训
- **做错了什么**：
  - 技术坑①：`@PreAuthorize` 方法级拒绝被 `GlobalExceptionHandler` 的兜底 `Exception` 处理器吞掉，返回 **HTTP 200/code 500** 而非 403 → 新增 `AccessDeniedException` 处理器（`@ResponseStatus(FORBIDDEN)`）修复。
  - 技术坑②：`AssignPermissionsRequest/AssignRolesRequest` 最初带 `@NotEmpty`，导致「清空角色权限/用户角色」被参数校验拦（整表替换语义应允许空=清空）→ 移除 `@NotEmpty`，由 `RbacTest.permission_instant_effect` 暴露并验证。
  - 流程失误③：给 `/api/users` 加 `@PreAuthorize` 后未同步评估既有测试 → 旧用例（普通用户当管理员调用户接口）从 200 变 403 回归红 → 在测试基类统一加 `adminToken()` 并清理 `sys_user_role`。
  - 流程失误④：PowerShell 里 `\"` 不是转义，构造 JSON body 出错 → 用字符串拼接 `'{"permissionIds":[' + $id + ']}'` 解决。
  - 工具坑⑤：`BCryptPasswordEncoder` 运行时依赖 commons-logging，临时生成哈希失败 → 改用自包含的 `BCrypt.hashpw/gensalt`。
- **吸取的教训**：
  - 引入方法级鉴权必须同批次改测试基类造「带权限用户」，否则整组回归红。
  - 「整表替换」语义的集合字段不要加非空校验，空集合=清空是合法操作。
  - PowerShell 构造含引号的 JSON 别用 `\"`，用字符串拼接或 `ConvertTo-Json`。

## 四、关键技术抉择
- **权限模型选「权限码」而非「菜单树」**（用户确认）：`sys_permission` 存权限标识字符串，`@PreAuthorize("hasAuthority('xxx')")`。5 表干净、贴合现有 `@EnableMethodSecurity`；放弃菜单树（超出 5 表、复杂度高）。
- **权限加载选「每请求查库」暂不引入 Redis**（用户确认）：两条 join 查询、无 N+1，管理后台可接受，权限变更即时生效；后续加 Redis（key=userId，分配/删角色时失效）即可。
- **超管用「绑定全部权限」而非硬编码 bypass**（用户确认）：新增权限码须在 init.sql 补行并给 ADMIN 绑定，否则超管也拿不到——此为本模式天然维护点。
- **关系表（user_role/role_permission）不做逻辑删除**：整表替换语义需物理删除，不继承 `BaseEntity.deleted`；保留自增 id 兼容 `BaseMapper`。
- **方法级拒权放 `GlobalExceptionHandler` 返回 403**：`@PreAuthorize` 在过滤器之后、`ExceptionTranslationFilter` 覆盖不到，必须由 `@RestControllerAdvice` 处理。
- **内置 ADMIN 角色禁止删除**（`RoleServiceImpl.delete` 校验 code），防止误删致权限失控。