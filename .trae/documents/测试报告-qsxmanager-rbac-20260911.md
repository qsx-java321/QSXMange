# QSXManager 完整测试报告（RBAC 完整权限设计 + 数据库适配审查）

- 测试日期：2026-09-11
- 项目分支：`master`，最新提交 `93418ab`（docs: README 更新至菜单管理阶段并新增会话总结）
- 环境：Java 21 / Spring Boot 3.5.16 / MyBatis-Plus 3.5.17 / MySQL 8.4（Docker 容器 `mysql`，数据卷 `mysql-data`，库 `QSXManager`，`localhost:3306`）
- 应用启动方式：`mvn spring-boot:run`，服务地址 `http://localhost:8080`
- 测试方式：**真实网络接口测试**——启动应用后用真实 HTTP 请求逐一访问业务接口，携带真实请求头/请求体，记录真实响应
- 响应结构：统一 `{code, message, data}`；`code=200` 成功、`401` 未登录、`403` 无权限、`1000+` 业务异常

---

## 一、数据库与 SQL 脚本适配审查

> 目的：核对 Docker MySQL 中的实际库表结构与 `sql/init.sql`、实体类、常量、Mapper 联表 SQL 是否一致，避免"脚本能跑但代码对不上"。

### 1.1 表结构与脚本一致性

| 表 | 实际列（information_schema 核对） | 与 init.sql | 唯一索引 |
|---|---|---|---|
| `sys_user` | id / email / password / nickname / status / create_time / update_time / deleted | ✅ 一致 | `uk_email(email)` |
| `sys_role` | id / code / name / description / status / create_time / update_time / deleted | ✅ 一致 | `uk_role_code(code)` |
| `sys_permission` | id / code / name / type / parent_id / path / component / icon / visible / sort / 审计字段 / deleted | ✅ 一致 | `uk_perm_code(code)` |
| `sys_user_role` | id / user_id / role_id / create_time（纯关系表，无 deleted） | ✅ 一致 | `uk_user_role(user_id,role_id)` + `idx(role_id)` |
| `sys_role_permission` | id / role_id / permission_id / create_time（纯关系表，无 deleted） | ✅ 一致 | `uk_role_perm(role_id,permission_id)` + `idx(permission_id)` |

### 1.2 实体与表字段映射（驼峰 → 下划线）

- `User`/`Role`/`Permission`：继承 `BaseEntity`（id、create_time、update_time、deleted，`@TableLogic` 逻辑删除）→ 与主表一致 ✅
- `UserRole`/`RolePermission`：独立实体（id、userId、roleId / permissionId、createTime），**无 deleted 字段** → 关联表走物理删除 ✅
- MyBatis-Plus 全局 `logic-delete-field: deleted` 仅作用于含该字段的实体，关联表查询不受影响 ✅

### 1.3 权限码与代码常量一致性

预置数据共 **23 条**（5 菜单 + 18 按钮权限），与 `PermissionConstants` 逐一核对：

| 归属菜单 | 权限码（parent_id 指向菜单 id） | 数量 |
|---|---|---|
| system（系统管理，顶级） | — | 1 菜单 |
| system-user（用户管理） | user:page / get / create / update / delete / assign-role | 6 |
| system-role（角色管理） | role:page / get / create / update / delete / assign | 6 |
| system-perm（权限管理） | perm:page / get | 2 |
| system-menu（菜单管理） | menu:tree / create / update / delete | 4 |
| **合计** | **5 菜单 + 18 按钮 = 23** | ✅ 与 PermissionConstants 完全对齐 |

### 1.4 预置数据与联表 SQL

- 预置：`admin@qsx.com`（status=0，deleted=0）、`ADMIN` 角色（status=0）、`sys_role_permission` 23 条（ADMIN 绑定全部菜单+权限）、`sys_user_role` 1 条 ✅
- `UserMapper.selectRoleCodes/selectPermissionCodes` 联表 SQL 所用字段（`ur.user_id`、`r.code`、`r.deleted`、`rp.role_id`、`rp.permission_id`、`p.code`、`p.deleted`）与表结构完全吻合 ✅
- 逻辑删除语义正确：主表逻辑删（`deleted=1`），关联表物理删，与代码 `deleteById` 行为一致 ✅

**适配审查结论：数据库与 SQL 脚本完全适配项目，无需任何结构修正。**

---

## 二、RBAC 完整权限设计流程

```
① 数据模型层  用户 ↔ 用户-角色(sys_user_role) ↔ 角色 ↔ 角色-权限(sys_role_permission) ↔ 权限(菜单+按钮，树形)
② 登录认证    邮箱+密码+状态(0-正常/1-禁用) → BCrypt 校验 → 签发 JWT(HS384, subject=email, claim=userId, 24h)
③ 请求鉴权    JWT过滤器解析token → 每请求实时查库加载角色码(ROLE_前缀)+权限码 → 写入SecurityContext
              → URL级 authenticated() + 方法级 @PreAuthorize(hasAuthority('xxx')) 双保险
④ 前端路由    GET /api/menus/current 按用户权限码过滤菜单树并补齐祖先链，前端动态生成路由
```

关键设计点：
- **每请求查库**加载权限（无缓存），角色/权限变更即时生效（见场景 B5/B6）；
- **菜单即权限**：菜单(MENU)与按钮(PERMISSION)共用 `sys_permission` 表，挂到角色即获权；
- **当前用户菜单树**按权限码过滤 + 自动补齐祖先（无直接权限也能看到父菜单入口）。

---

## 三、真实网络接口测试流程

> 测试数据统一使用 `test-*` / 临时邮箱，测试结束后已重置数据库。请求头均携带 `Authorization: Bearer <token>`（场景内简写为"携带 token"）。

### 场景 A：超管全流程（用户/角色/权限 CRUD）

**A1 登录**
- **URL**：`POST http://localhost:8080/auth/login`
- **携带**：Body `{"email":"admin@qsx.com","password":"admin123"}`
- **返回**：`HTTP 200`，`code=200`，`data.roles=["ADMIN"]`，`data.permissions` 共 **23 项**（5 菜单 + 18 按钮），`data.token` JWT

**A2 新增用户**
- **URL**：`POST http://localhost:8080/api/users`
- **携带**：token；Body `{"email":"a1@demo.com","password":"abc123","nickname":"A1用户","status":0}`
- **返回**：`HTTP 200`，`code=200`，`data{id=2, email=a1@demo.com, status=0}`

**A3 用户详情**
- **URL**：`GET http://localhost:8080/api/users/2`
- **携带**：token
- **返回**：`HTTP 200`，`data{id=2, email=a1@demo.com}`（不暴露密码字段）

**A4 修改用户**
- **URL**：`PUT http://localhost:8080/api/users/2`
- **携带**：token；Body `{"email":"a1@demo.com","nickname":"改名","status":1}`
- **返回**：`HTTP 200`，`code=200`，`data.nickname="改名"`

**A5 新增角色**
- **URL**：`POST http://localhost:8080/api/roles`
- **携带**：token；Body `{"code":"test-role-a","name":"测试角色A"}`
- **返回**：`HTTP 200`，`code=200`，`data{id=2, code=test-role-a}`

**A6 为角色分配权限**
- **URL**：`PUT http://localhost:8080/api/roles/2/permissions`
- **携带**：token；Body `{"permissionIds":[6,12]}`（6=user:page，12=role:page）
- **返回**：`HTTP 200`，`code=200`，`data=null`（整表替换成功）

**A7 为用户绑定角色**
- **URL**：`PUT http://localhost:8080/api/users/2/roles`
- **携带**：token；Body `{"roleIds":[2]}`
- **返回**：`HTTP 200`，`code=200`，`data=null`

**A8 删除用户**
- **URL**：`DELETE http://localhost:8080/api/users/2`
- **携带**：token
- **返回**：`HTTP 200`，`code=200`（逻辑删除 + 释放邮箱）

**A9 删除角色**
- **URL**：`DELETE http://localhost:8080/api/roles/2`
- **携带**：token
- **返回**：`HTTP 200`，`code=200`（无绑定用户可删，先清关联再逻辑删除）

### 场景 B：细粒度授权 + 权限即时生效

> 构造：用户 b1@demo.com，绑定角色 test-role-c（仅授 `user:page`）。

**B1 用户分配角色**
- **URL**：`PUT http://localhost:8080/api/users/{uid}/roles`，Body `{"roleIds":[rid]}`
- **返回**：`HTTP 200`，`code=200`

**B2 普通用户登录（确认权限码）**
- **URL**：`POST http://localhost:8080/auth/login`，Body `{"email":"b1@demo.com","password":"abc123"}`
- **返回**：`data.permissions=["user:page"]`（仅有授的一项）

**B3 有权限接口放行**
- **URL**：`GET http://localhost:8080/api/users`（携带 b1 token）
- **返回**：`HTTP 200`，`code=200`（拥有 `user:page` 放行）

**B4 无权限接口拒绝**
- **URL**：`GET http://localhost:8080/api/roles`（携带 b1 token）
- **返回**：`HTTP 403`，Body `{"code":403,"message":"没有操作权限","data":null}`（无 `role:page`，`@PreAuthorize` 拦截）

**B5 补授权限 → 即时生效**
- **URL**：`PUT http://localhost:8080/api/roles/{rid}/permissions`，Body `{"permissionIds":[user:page,role:page]}`（管理员）
- **随后**：`GET http://localhost:8080/api/roles`（**同一 b1 token，未重新登录**）
- **返回**：`HTTP 200`，`code=200`（权限变更即时生效，无需重新登录）

**B6 撤销权限 → 即时失效**
- **URL**：`PUT http://localhost:8080/api/roles/{rid}/permissions`，Body `{"permissionIds":[role:page]}`（管理员，移除 user:page）
- **随后**：`GET http://localhost:8080/api/users`（同一 b1 token）
- **返回**：`HTTP 403`（权限被撤销即时失效）

### 场景 D：菜单树按权限过滤（前端动态路由）

> 构造：用户 d1@demo.com，绑定角色 test-role-d（仅授 `system-user` 菜单 + `user:page` 按钮）。

**D1 当前用户菜单树**
- **URL**：`GET http://localhost:8080/api/menus/current`
- **携带**：token（d1）
- **返回**：`HTTP 200`，`data` 为树形：
  - `系统管理(system)` → 子节点 **仅 `用户管理(system-user)`**
  - 不含角色/权限/菜单管理子菜单（未授权过滤正确）
  - `system` 为自动补齐的**祖先节点**（用户无 system 直接权限也能看到父菜单入口）✅

### 场景 E：异常与安全边界

| 用例 | URL | 携带 | 返回 |
|---|---|---|---|
| E1 密码错误 | `POST /auth/login` | Body `{"email":"d1@demo.com","password":"wrong"}` | `HTTP 200`，`code=1002 邮箱或密码错误` |
| E2 禁用账号 | `PUT /api/users/{id}` 置 status=1 后 `POST /auth/login` | 正确密码 | `HTTP 200`，`code=1003 账号已被禁用` |
| E3 无 token | `GET /api/users` | 无请求头 | `HTTP 401`，`code=401 未登录或登录已过期` |
| E4 伪造 token | `GET /api/users` | `Authorization: Bearer xxx.yyy.zzz` | `HTTP 401`，`code=401`（签名非法静默拒登） |

---

## 四、测试结论

| 项 | 结果 |
|---|---|
| 数据库/脚本适配（表结构/索引/实体/权限码/联表 SQL） | ✅ 完全一致，无需修正 |
| 场景 A 超管全流程（登录/用户 CRUD/角色+权限/绑定/删除） | ✅ 全部 200 |
| 场景 B 细粒度授权（有权限放行 / 无权限 403） | ✅ |
| 场景 B 权限即时生效（补授/撤销，无需重新登录） | ✅ |
| 场景 D 菜单树权限过滤（含祖先补齐） | ✅ |
| 场景 E 安全边界（1002/1003/401 伪造 token） | ✅ |

**结论**：RBAC 完整权限设计（数据模型 → 登录认证 → 请求鉴权双保险 → 前端菜单路由）在真实网络接口层验证无误，且与 Docker 数据库、`sql/init.sql` 完全适配。**无需任何代码修改，可交付对接前端。**

---

## 五、测试过程备注

- 场景 A 中 A6/A7 初次执行返回 500，排查确认是 PowerShell 测试脚本内嵌套 `$()` 与引号转义破损导致请求体损坏，**非应用缺陷**；改用干净脚本（变量拼接请求体）后返回 200，已复核。
- 应用日志中文在 PowerShell 控制台显示乱码为客户端编码问题，服务端与数据库存储均为正常 UTF-8。
- 测试结束后已重置数据库（重新执行 `sql/init.sql`），恢复预置状态：1 个超管用户 / 5 菜单 / 18 按钮权限 / 23 条 ADMIN 关联。
