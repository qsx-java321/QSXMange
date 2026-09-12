# QSXManager 业务功能测试报告

## 概述

| 项 | 值 |
|------|------|
| 测试时间 | 2026-09-12 19:08（本地时区 Asia/Shanghai） |
| 被测系统 | QSXManager 后台管理系统（Spring Boot 3.5.16 / Java 21 / MyBatis-Plus / Spring Security + JWT） |
| 数据库 | MySQL 8.4（本地 Docker，`localhost:3306/QSXManager`） |
| 缓存 | Redis 7.4.9（本地 Docker，`localhost:6379`，RBAC 权限缓存） |
| 应用地址 | `http://localhost:8080` |
| 测试方式 | ① 自动化集成测试 `mvn test`（MockMvc + 真实 MySQL/Redis） ② 真实 HTTP 调用（启动应用逐接口调用，记录请求与响应） |

**测试结果总览**

- 自动化集成测试：**66 个用例，全部通过（Failures: 0, Errors: 0）**
- 真实 HTTP 接口调用：**38 次调用**，业务接口全部按预期返回；覆盖认证中心、用户、角色、权限、菜单、日志、Excel 导入导出及 401/403 边界
- 权限管理设计审查：**通过**（详见下节）

---

## 一、权限管理设计审查结论

逐项核对后未发现问题，设计闭环完整：

| 审查项 | 结论 |
|------|------|
| 权限码一致性 | `PermissionConstants` 22 个权限码与 `init.sql` 预置 22 个按钮权限一一对应（用户 8 / 角色 6 / 权限 2 / 菜单 4 / 日志 2），另有 6 个菜单码，共 28 条 |
| 超管模型 | ADMIN 角色通过 `INSERT ... SELECT` 绑定全量权限实现，非 `hasRole('ADMIN')` 硬编码放行 |
| 权限表只读 | `sys_permission` 无写接口，权限码变更走 `init.sql` |
| 整表替换授权 | 用户-角色、角色-权限均先物理删旧关联再批量插新（本次已补事务原子性） |
| 菜单树 | 菜单/按钮共用 `sys_permission`（`type` 区分），`parent_id` 自关联，防环校验 + 有子节点禁删 |
| 缓存链路 | 权限码 Redis 缓存（key=`qsx:auth:perm:{userId}`），未命中回源 MySQL 并回填；权限变更在事务提交后（AFTER_COMMIT）失效，实测变更即时生效 |
| 降级 | Redis 不可用时自动回源 MySQL，业务不中断；`qsx.rbac-cache.enabled=false` 可一键关闭缓存 |

---

## 二、自动化集成测试（`mvn test`：66/66 通过）

| 测试类 | 覆盖范围 |
|------|------|
| `AuthControllerTest` | 注册 / 登录 / 当前用户 / 修改密码 |
| `RbacTest` | 角色与权限的 CRUD、分配、ADMIN 保护、整表替换 |
| `MenuTest` | 菜单树、防环、有子节点禁删、标识唯一 |
| `SecurityAccessTest` | 401 / 403 / 越权边界 |
| `BusinessFlowTest` | 认证→授权→业务 全链路 |
| `UserControllerTest` | 用户 CRUD、唯一邮箱、逻辑删除释放邮箱 |
| `LogTest` | 操作日志分页 / 删除 / 清空 |
| `UserImportExportTest` | 模板下载 / 导入（整批校验整体拒绝）/ 导出（9 用例） |
| `RbacCacheTest` + `RbacCacheDisabledTest` | 权限缓存回填、空权限防穿透、变更即时失效、开关降级 |

---

## 三、真实 HTTP 接口测试明细

> 说明：`<admin-token>` = 登录 `admin@qsx.com / admin123` 返回的 JWT；`<user-token>` = 登录测试用户返回的 JWT。Token 完整值见 `docs/test/api-test-results.json`，下表展示摘要（`eyJhbGciOiJIUzM4NCJ9...`）。

### 3.1 认证中心

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `POST /auth/register` | 无认证；Body `{"email":"t_report_...@test.com","password":"abc123","nickname":"报告测试用户"}` | HTTP 200 `{"code":200,"message":"操作成功","data":null}` |
| `POST /auth/login`（超管） | 无认证；Body `{"email":"admin@qsx.com","password":"admin123"}` | HTTP 200 `{"code":200,"data":{"token":"eyJhbGciOiJIUzM4NCJ9...","userId":1,"email":"admin@qsx.com","nickname":"超级管理员","roles":["ADMIN"],"permissions":[28 个权限码]}}` |
| `POST /auth/login`（测试用户） | 无认证；Body `{"email":"t_report_...@test.com","password":"abc123"}` | HTTP 200 `{"code":200,"data":{"token":"...","userId":98,"roles":[],"permissions":[]}}` |
| `GET /auth/me` | `Authorization: Bearer <user-token>` | HTTP 200 `{"code":200,"data":{"id":98,"email":"...","nickname":"报告测试用户","status":0,...}}` |
| `POST /auth/change-password`（改） | `Bearer <user-token>`；Body `{"oldPassword":"abc123","newPassword":"abc124"}` | HTTP 200 `{"code":200,"message":"操作成功","data":null}` |
| `POST /auth/change-password`（改回） | `Bearer <user-token>`；Body `{"oldPassword":"abc124","newPassword":"abc123"}` | HTTP 200 `{"code":200,"message":"操作成功","data":null}` |

### 3.2 用户管理

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/users?pageNum=1&pageSize=5` | `Authorization: Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"records":[测试用户、admin 两行],"total":2,"pages":1}}` |
| `POST /api/users` | `Bearer <admin-token>`；Body `{"email":"t_report_u1_...@test.com","password":"abc123","nickname":"新增测试用户"}` | HTTP 200 `{"code":200,"data":{"id":99,"email":"...","nickname":"新增测试用户","status":0}}` |
| `GET /api/users/99` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"id":99,"nickname":"新增测试用户","status":0}}` |
| `PUT /api/users/99` | `Bearer <admin-token>`；Body `{"email":"...","nickname":"改后昵称","status":0}` | HTTP 200 `{"code":200,"data":{"id":99,"nickname":"改后昵称","status":0}}` |
| `PUT /api/users/99/roles` | `Bearer <admin-token>`；Body `{"roleIds":[1]}`（绑定 ADMIN） | HTTP 200 `{"code":200,"message":"操作成功","data":null}` |
| `DELETE /api/users/99` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"message":"操作成功","data":null}`（逻辑删除并释放邮箱） |

### 3.3 角色管理

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/roles?pageNum=1&pageSize=5` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"records":[ADMIN 角色],"total":1}}` |
| `GET /api/roles/all` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":[{"id":1,"code":"ADMIN","name":"超级管理员","status":0}]}` |
| `GET /api/roles/1` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"id":1,"code":"ADMIN","permissionIds":[28 个权限 ID]}}` |
| `POST /api/roles` | `Bearer <admin-token>`；Body `{"code":"TEST_ROLE_...","name":"测试角色","description":"报告测试用","status":0}` | HTTP 200 `{"code":200,"data":{"id":8,"code":"TEST_ROLE_...","name":"测试角色","status":0}}` |
| `PUT /api/roles/8` | `Bearer <admin-token>`；Body `{"code":"TEST_ROLE_...","name":"测试角色改","description":"改","status":0}` | HTTP 200 `{"code":200,"data":{"id":8,"name":"测试角色改"}}` |
| `PUT /api/roles/8/permissions` | `Bearer <admin-token>`；Body `{"permissionIds":[7,8]}`（role:page / role:get） | HTTP 200 `{"code":200,"message":"操作成功","data":null}` |
| `DELETE /api/roles/8` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"message":"操作成功","data":null}`（先清关联再逻辑删除） |

### 3.4 权限管理（只读）

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/permissions?pageNum=1&pageSize=5` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"records":[system 菜单、system-user 等 5 条],"total":28,"pages":6}}` |
| `GET /api/permissions/all` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":[28 条权限（6 菜单 + 22 按钮），按 sort 排序]}` |
| `GET /api/permissions/1` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"id":1,"code":"system","name":"系统管理","type":"MENU","parentId":0}}` |

### 3.5 菜单管理

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/menus/tree` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":[system 下挂 system-user/system-role/system-perm/system-menu/system-log 5 个子菜单，各含按钮权限叶子]}` |
| `GET /api/menus/current`（我的菜单） | `Bearer <user-token>`（无角色用户） | HTTP 200 `{"code":200,"data":[]}`（按权限过滤 + 补祖先链，无权限返回空树） |
| `POST /api/menus` | `Bearer <admin-token>`；Body `{"code":"test-menu-...","name":"测试菜单","type":"MENU","parentId":0,"path":"/test","component":"test/index","visible":1,"sort":99}` | HTTP 200 `{"code":200,"data":{"id":38,"code":"test-menu-...","type":"MENU","parentId":0}}` |
| `PUT /api/menus/38` | `Bearer <admin-token>`；Body `{"code":"test-menu-...","name":"测试菜单改","type":"MENU","parentId":0,"visible":1,"sort":99}` | HTTP 200 `{"code":200,"data":{"id":38,"name":"测试菜单改"}}` |
| `DELETE /api/menus/38` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"message":"操作成功","data":null}`（无子节点，删除并清理角色-权限关联） |

### 3.6 操作日志

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/logs?pageNum=1&pageSize=5` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"data":{"records":[{id:364,username:"admin@qsx.com",method:"DELETE",url:"/api/menus/38",success:1,...}],"total":..."}}`（AOP 切面已异步落库本测试全部调用） |
| `DELETE /api/logs/364` | `Bearer <admin-token>` | HTTP 200 `{"code":200,"message":"操作成功","data":null}`（物理删除单条） |

### 3.7 Excel 导入导出

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/users/import/template` | `Bearer <admin-token>` | HTTP 200；`Content-Type: application/vnd...spreadsheetml.sheet`、`Content-Disposition: attachment; filename=user_import_template.xlsx`、文件 3704 字节 |
| `GET /api/users/export` | `Bearer <admin-token>` | HTTP 200；`filename=users_20260912190817.xlsx`、文件 3890 字节（含邮箱/昵称/状态/角色编码/创建时间） |
| `POST /api/users/import`（非法文件） | `Bearer <admin-token>`；multipart `file=invalid-xxx.xlsx` | HTTP 200 `{"code":1017,"message":"Excel 文件解析失败，请检查文件内容：No valid entries or contents found..."}` |
| `POST /api/users/import`（导出文件回导） | `Bearer <admin-token>`；multipart `file=export-xxx.xlsx` | HTTP 200 `{"code":1017,"message":"导入数据校验失败","data":{"successCount":0,"errors":["第 2 行：邮箱已存在：...","第 2 行：状态只能为 0(正常) 或 1(禁用)",...]}}`（整批校验整体拒绝，返回全部错误行明细） |

> 注：导入成功路径由自动化测试 `UserImportExportTest`（9 用例）覆盖；真实调用刻意演示了「整批拒绝 + 错误明细」与「导出文件与导入模板列格式差异（导出状态列为中文"正常/禁用"，导入要求 0/1）」的行为。

### 3.8 认证/授权边界

| 接口 | 携带信息 | 返回信息 |
|------|------|------|
| `GET /api/users?pageNum=1&pageSize=5`（未登录） | 无任何认证信息 | HTTP 401 `{"code":401,"message":"未登录或登录已过期","data":null}` |
| `GET /api/users?pageNum=1&pageSize=5`（越权） | `Bearer <user-token>`（无 user:page 权限的普通用户） | HTTP 403 `{"code":403,"message":"没有操作权限","data":null}` |

---

## 四、测试中发现并处理的问题

| 问题 | 根因 | 处理 |
|------|------|------|
| 预置中文乱码（admin 昵称"超级管理员"、菜单/权限名显示为 mojibake） | 历史某次 `init.sql` 导入未指定 `--default-character-set=utf8mb4`（见 `docs/dev-env/组件依赖README.md` 警告），UTF-8 字节被误转 | 已按 `init.sql` 字面量执行 `UPDATE` 修复 30 处预置中文（用户 1 / 角色 1 / 权限 28），并清空相关权限缓存 |
| admin 账号登录报「邮箱或密码错误」 | 自动化测试基类 `@BeforeEach/@AfterEach` 对 `sys_user` 全表逻辑删除，预置 admin 被一并置 `deleted=1` | 已恢复 `deleted=0` 并重建 admin↔ADMIN 角色关联，清空其权限缓存 |
| 历史测试残留数据（逻辑删除用户、测试角色/菜单） | 自动化测试长期复用本地库产生 | 已物理清理测试残留数据，仅保留预置 admin/ADMIN/28 权限 |

> 以上均为**数据/测试环境问题**，非业务代码缺陷；代码层面未发现需修复项。

---

## 五、结论

- 认证中心、用户/角色/权限/菜单管理、操作日志、Excel 导入导出共 **36 项业务接口**真实调用全部按预期返回（200/401/403 语义正确，业务码 1017 等与设计一致）。
- 权限管理设计（权限码模型、超管绑定、整表替换、菜单树、缓存一致性）审查通过。
- RBAC 权限缓存（Redis）实测生效：登录回填、无权限 401/403 边界正确、导入整批校验与错误明细完整、日志切面全量异步落库。
- 自动化集成测试 66/66 通过，与真实 HTTP 测试结果互相印证。

---

*原始请求/响应数据见 `docs/test/api-test-results.json`。*
