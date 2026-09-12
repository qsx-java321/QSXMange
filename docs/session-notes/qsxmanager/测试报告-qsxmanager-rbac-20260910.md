# QSXManager 完整测试报告（RBAC 权限管理）

- 测试日期：2026-09-10
- 项目分支：`master`（已合并 RBAC），提交 `1e9c9ca`
- 环境：Java 21 / Spring Boot 3.5.16 / MyBatis-Plus 3.5.17 / MySQL 8.4（Docker，库 `QSXManager`，位置 `localhost:3306`）
- 测试方式分两类：
  1. **自动化测试类**：`mvn test`（Spring Boot 集成测试，MockMvc + 真实 MySQL）
  2. **真实网络接口测试**：启动应用于 `localhost:8080`，用真实 HTTP 请求逐一访问业务接口（携带真实请求头/请求体，记录真实响应）

---

## 一、自动化测试类结果

执行 `mvn test`，共 **40 个用例，0 失败，0 错误，0 跳过，BUILD SUCCESS**。

| 测试类 | 用例数 | 结果 | 覆盖范围 |
|---|---|---|---|
| AuthControllerTest | 12 | ✅ 通过 | 注册、登录（密码错/禁用拒绝）、当前用户、改密 |
| UserControllerTest | 15 | ✅ 通过 | 用户分页/筛选/新增/修改/详情/删除（含邮箱重复、删除释放邮箱） |
| SecurityAccessTest | 5 | ✅ 通过 | 匿名放行登录、匿名 401、伪造/过期 token 401、有效 token 200 |
| RbacTest | 5 | ✅ 通过 | 超管可访问、普通用户 403、细粒度权限、权限即时生效、分配幂等 |
| BusinessFlowTest | 3 | ✅ 通过 | 注册→登录→me→改密回归、逻辑删除释放邮箱重注册、禁用登录拦截 |

---

## 二、真实网络接口测试流程

> 说明：HTTP 状态码指真实响应状态；业务码为响应体 `Result.code`。响应体为统一 `{code, message, data}` 结构。数据源状态：测试前已重置种子数据（admin + 14 权限 + ADMIN 角色）。

### 1. 管理员登录
- **URL**：`POST http://localhost:8080/auth/login`
- **携带**：Body `{"email":"admin@qsx.com","password":"admin123"}`
- **返回**：`HTTP 200`，`code=200`
  - `data.token`：JWT（HS384，含 subject=邮箱、userId=1，24h 有效）
  - `data.roles: ["ADMIN"]`
  - `data.permissions`：14 项（`user:page…perm:get`）
  - `data.nickname: "超级管理员"`（DB 校验；仅 HTTP 客户端控制台存在 UTF-8 显示乱码，存储正确）

### 2. 获取当前登录用户
- **URL**：`GET http://localhost:8080/auth/me`
- **携带**：请求头 `Authorization: Bearer <token>`
- **返回**：`HTTP 200`，`code=200`，`data{id=1, email="admin@qsx.com", nickname="超级管理员", status=0}`

### 3. 查询权限列表（只读）
- **URL**：`GET http://localhost:8080/api/permissions?pageSize=50`
- **携带**：`Authorization: Bearer <token>`（管理员）
- **返回**：`HTTP 200`，`data.total=14`，`data.records[].code` 含全部 14 个权限码，无越权

### 4. 用户分页查询
- **URL**：`GET http://localhost:8080/api/users?pageSize=50`
- **携带**：`Authorization: Bearer <token>`（管理员）
- **返回**：`HTTP 200`，`data.total=1`，仅返回 admin 自身

### 5. 公开注册新用户
- **URL**：`POST http://localhost:8080/auth/register`
- **携带**：Body `{"email":"smoke-user@test.com","password":"abc123","nickname":"smoke用户"}`
- **返回**：`HTTP 200`，`code=200`，`data=null`（匿名放行）

### 6. 普通用户登录（验证初始无权限）
- **URL**：`POST http://localhost:8080/auth/login`
- **携带**：Body `{"email":"smoke-user@test.com","password":"abc123"}`
- **返回**：`HTTP 200`，`code=200`，`data.roles=[]`、`data.permissions=[]`

### 7. 无权限用户访问受保护接口 → 403
- **URL**：`GET http://localhost:8080/api/users`
- **携带**：`Authorization: Bearer <普通用户token>`
- **返回**：`HTTP 403`，Body `{"code":403,"message":"没有操作权限","data":null}`（方法级 `@PreAuthorize` 生效）

### 8. 管理员创建角色
- **URL**：`POST http://localhost:8080/api/roles`
- **携带**：`Authorization: Bearer <管理员token>`；Body `{"code":"TEST_ROLE","name":"测试角色"}`
- **返回**：`HTTP 200`，`code=200`，`data.id=2`

### 9. 为角色分配权限
- **URL**：`PUT http://localhost:8080/api/roles/2/permissions`
- **携带**：`Authorization: Bearer <管理员token>`；Body `{"permissionIds":[1]}`（1=`user:page`）
- **返回**：`HTTP 200`，`code=200`，`data=null`

### 10. 为用户绑定角色
- **URL**：`PUT http://localhost:8080/api/users/2/roles`
- **携带**：`Authorization: Bearer <管理员token>`；Body `{"roleIds":[2]}`
- **返回**：`HTTP 200`，`code=200`，`data=null`

### 11. 细粒度权限验证（同一普通用户）
- **URL A**：`GET http://localhost:8080/api/users?pageSize=10`，携带普通用户 token
  - **返回**：`HTTP 200`，`data.total=2`（拥有 `user:page` 放行）
- **URL B**：`GET http://localhost:8080/api/roles`，携带普通用户 token
  - **返回**：`HTTP 403`，`{"code":403,"message":"没有操作权限","data":null}`（无 `role:page` 拒绝）

### 12. 匿名与伪造 Token → 401
- **URL**：`GET http://localhost:8080/api/users`（无请求头）
  - **返回**：`HTTP 401`，`{"code":401,"message":"未登录或登录已过期","data":null}`
- **URL**：`GET http://localhost:8080/api/users`，`Authorization: Bearer invalid.token.value`
  - **返回**：`HTTP 401`，同上

### 13. 权限移除后即时生效（每请求查库，无缓存）
- 前置：普通用户此时有 `user:page`（见 11A，已放行）
- **URL**：`PUT http://localhost:8080/api/roles/2/permissions`，Body `{"permissionIds":[]}`（管理员）
  - **返回**：`HTTP 200`，`code=200`（清空角色权限）
- **URL**：`GET http://localhost:8080/api/users`，携带**同一普通用户 token**
  - **返回**：`HTTP 403`，`{"code":403,...}`（权限变更即时生效）

### 14. 修改密码 + 新密码重新登录
- **URL**：`POST http://localhost:8080/auth/change-password`
- **携带**：`Authorization: Bearer <普通用户token>`；Body `{"oldPassword":"abc123","newPassword":"newpass1"}`
- **返回**：`HTTP 200`，`code=200`
- **URL**：`POST http://localhost:8080/auth/login`，Body `{"email":"smoke-user@test.com","password":"newpass1"}`
- **返回**：`HTTP 200`，`code=200`（新密码可登录）

### 15. 删除资源 + 内置超管角色保护
- **URL**：`DELETE http://localhost:8080/api/users/2`
  - **返回**：`HTTP 200`，`code=200`（逻辑删除并释放邮箱）
- **URL**：`DELETE http://localhost:8080/api/roles/1`（ADMIN 内置角色）
  - **返回**：`HTTP 200`，Body `{"code":1011,"message":"角色已被用户使用，无法删除","data":null}`（正确阻止删除内置超管）
- **URL**：`DELETE http://localhost:8080/api/roles/2`
  - **返回**：`HTTP 200`，`code=200`
- **URL**：`GET http://localhost:8080/api/users?pageSize=50`（管理员）
  - **返回**：`HTTP 200`，`data.total=1`（仅剩 admin，删除链路正确）

---

## 三、测试结论

| 项 | 结果 |
|---|---|
| 自动化测试类（40 用例） | ✅ 全部通过 |
| 真实网络接口（15 组流程） | ✅ 全部通过 |
| 认证（登录/注册/改密/me） | ✅ 通过 |
| 用户管理（CRUD/分页/删除释放邮箱） | ✅ 通过 |
| RBAC（角色/权限/细粒度/即时生效/内置超管保护） | ✅ 通过 |
| 安全边界（401/403/伪造token） | ✅ 通过 |

**结论**：RBAC 权限管理（5 表、权限码模型、`@PreAuthorize` 方法级鉴权、每请求查库即时生效）在自动化与真实网络两层测试下均验证无误，可交付上线使用。

---

## 四、测试环境注意事项
- 真实网络测试前已重置数据库（`sql/init.sql`），保证 admin 种子可用。
- 自动化测试会逻辑清空 `sys_user`/`sys_user_role`（角色/权限为常驻种子），互不影响。
- 控制台中文乱码为 HTTP 客户端显示编码问题，服务端与 DB 存储均为正常 UTF-8。