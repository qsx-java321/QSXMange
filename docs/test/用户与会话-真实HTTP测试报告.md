# 用户管理 & 会话管理 — 双轨测试报告

## 概述

| 项 | 值 |
|------|------|
| 测试时间 | 2026-09-17（本地时区 Asia/Shanghai） |
| 被测系统 | QSXManager 后台管理系统（Spring Boot 3.5.16 / Java 21 / MyBatis-Plus / Spring Security + Redis 双 token 会话） |
| 测试方式 | **双轨**：① Maven 自动化集成测试（MockMvc + 真实 MySQL/Redis）② 真实 HTTP 逐接口调用（启动应用 `localhost:8080`） |
| 数据库 | MySQL 8.4（本地 Docker，`localhost:3306/QSXManager`） |
| 缓存 | Redis 7.4.9（本地 Docker，`localhost:6379`）—— 认证链路硬依赖 |
| 应用地址 | `http://localhost:8080` |
| 内置超管 | `admin@qsx.com` / `admin123`（id=1，绑定 ADMIN 角色 id=1，全量权限） |

**结论总览**
- 轨道一（Maven）：**130/130 全绿**（Failures/Errors/Skipped = 0）。
- 轨道二（真实 HTTP）：**5 组共 40+ 次调用全部符合预期**（认证会话 / 用户 CRUD / 角色分配 / 踢人 / 保护矩阵），Redis 三键形态、TTL 与设计规格一致。
- 测试数据清理完毕：测试用户 0、Redis 会话键 0，内置超管与种子角色完好（未破坏）。

---

## 轨道一：Maven 自动化集成测试

```bash
mvn -o test   # 结果：Tests run: 130, Failures: 0, Errors: 0, Skipped: 0
```

本模块相关测试类（全量含）：

| 测试类 | 用例数 | 覆盖 |
|------|------|------|
| `SessionLuaTest` | 13 | 会话层（直连 Redis）：三键形态/TTL、单端覆盖、轮换失效、重放拒绝、并发双花、绝对上限、Lua 参数守卫 |
| `AuthSessionTest` | 6 | 会话生命周期（HTTP）：登出/禁用/删除后旧 AT 立即 401 且三键清空、普通编辑不误踢、改密强制重登、改邮箱令牌可用、再次登录覆盖 |
| `AuthRefreshTest` | 11 | 刷新轮换、旧 AT 失效、未知/畸形令牌、用户不存在与禁用分支、登出失效、绝对上限、单端 |
| `AuthControllerTest` | 12 | 注册 / 登录 / 当前用户 / 修改密码 |
| `SecurityAccessTest` | 6 | 401 边界、匿名放行、伪造令牌、已吊销令牌立即 401、AT 键 TTL 接线 |
| `UserControllerTest` | 16 | 用户 CRUD、唯一邮箱、逻辑删除释放邮箱、分页筛选、非法 status |
| `UserKickTest` | 11 | 踢人、禁用/解冻、删除联动、1020/1021/1022 保护、非法 status 不可绕过 |
| `UserEdgeTest` | 15 | 创建参数校验、分页越界、assignRoles 边界与回滚、空角色清空+权限即时回收、删除会话完整断言、**delete 超管 1025/自保 1022** |
| `RbacTest` / `RbacCacheTest`+`RbacCacheDisabledTest` | 14 | 角色权限 CRUD、分配、整表替换、缓存失效、停用角色回收、标识不可变（1023/1024）、开关降级 |

---

## 轨道二：真实 HTTP 逐接口明细

> 记录格式：**请求**（方法 + URL + Authorization + 参数/body）→ **响应**（HTTP 状态码 + JSON 中的 code/message/data 摘要）。原始数据见 `docs/test/用户与会话-真实HTTP-api-results.json`。

### A. 认证与会话（`/auth/*`）

| # | 操作（请求） | 返回信息 |
|---|------|------|
| A1a | `POST /auth/register` `{"email":"httpa-xxx@test.com","password":"abc123"}` | HTTP 200 `code=200` |
| A1b | `POST /auth/register`（同一邮箱重复） | HTTP 200 `code=1001 该邮箱已被注册` |
| A2 | `POST /auth/login` `{"email":"admin@qsx.com","password":"admin123"}` | HTTP 200 `code=200`，`data` 含 `token`/`refreshToken`/`userId=1`/`roles:["ADMIN"]`/`permissions:[29 项]` |
| A3a | `POST /auth/login` 密码错误 | HTTP 200 `code=1002` |
| A3b | `POST /auth/login` 被禁账号 | HTTP 200 `code=1003 账号已被禁用` |
| A4 | `GET /auth/me`（Bearer 超管 at） | HTTP 200 `code=200`，`data.email=admin@qsx.com` |
| A5a | `POST /auth/refresh` `{"refreshToken":"<rt>"}` | HTTP 200 `code=200`，返回新 `token`+`refreshToken` |
| A5b | `GET /auth/me`（**轮换前旧 at**） | **HTTP 401**（旧 AT 随轮换立即失效） |
| A5c | `POST /auth/refresh`（**重放旧 rt**） | HTTP 200 `code=1019` |
| A6a | `POST /auth/refresh` `{}`（缺参） | HTTP 200 `code=400 刷新令牌不能为空` |
| A6b | `POST /auth/refresh` `{"refreshToken":"<64位hex未签发>"}` | HTTP 200 `code=1019` |
| A6c | `POST /auth/refresh` `{"refreshToken":"forged-token-value"}`（形态非法） | HTTP 200 `code=1019` |
| A7a | `POST /auth/logout`（Bearer 有效 at） | HTTP 200 `code=200` |
| A7b | `GET /auth/me`（登出后同一 at） | **HTTP 401** |
| A7c | `POST /auth/refresh`（登出后 rt） | HTTP 200 `code=1019` |
| A8a | `POST /auth/change-password` `{"oldPassword":"abc123","newPassword":"newpass123"}` | HTTP 200 `code=200` |
| A8b | `GET /auth/me`（改密前 at） | **HTTP 401** |
| A8c | `POST /auth/login`（旧密码） | HTTP 200 `code=1002` |
| A8d | `POST /auth/login`（新密码） | HTTP 200 `code=200` |
| A9a | 同账号第二次登录后，`GET /auth/me`（第一次 at） | **HTTP 401**（单端覆盖） |
| A9b | 同账号第二次登录后，`POST /auth/refresh`（第一次 rt） | HTTP 200 `code=1019` |
| A9c | `GET /auth/me`（第二次 at） | HTTP 200 |
| A10 | Redis 实测 | 见下 |

**A10 Redis 三键实测（redis-cli）**

| Key | TTL | Value/Fields |
|------|------|------|
| `qsx:auth:at:{at}` | **1800s** | userId |
| `qsx:auth:rt:{rt}` | **604800s** | userId |
| `qsx:auth:session:{userId}` | **604800s** | `{accessToken, refreshToken, firstLoginTs}` |

### B. 用户管理 CRUD（`/api/users`，Bearer 超管 at）

| # | 操作（请求） | 返回信息 |
|---|------|------|
| B1a | `GET /api/users` | HTTP 200 `code=200`，`data.total=7` |
| B1b | `GET /api/users?email=b` | HTTP 200 `data.total=5`（模糊筛选） |
| B1c | `GET /api/users?status=1` | HTTP 200 `data.total=1`（精确筛选） |
| B1d | `GET /api/users?pageSize=0`（越界） | HTTP 200 `code=200`（不 500） |
| B2a | `GET /api/users/{id}` | HTTP 200 `code=200` |
| B2b | `GET /api/users/99999` | HTTP 200 `code=1004` |
| B3a | `POST /api/users` `{"email":"httpb1-xx@test.com","password":"abc123","status":0}` | HTTP 200 `code=200`，`data.id=528` |
| B3b | `POST /api/users` 弱密码 `abc12` | HTTP 200 `code=400`（参数校验，不落库） |
| B3c | `POST /api/users` 非法邮箱 `abc` | HTTP 200 `code=400` |
| B3d | `POST /api/users` 邮箱重复 | HTTP 200 `code=1001` |
| B4a | `PUT /api/users/{id}` 改邮箱+昵称 status=0 | HTTP 200 `code=200` |
| B4b | `PUT /api/users/{id}` 改成他人邮箱 | HTTP 200 `code=1001` |
| B4c | `PUT /api/users/99999` | HTTP 200 `code=1004` |
| B5 | `PUT /api/users/{id}` 普通编辑（status=0，改昵称） | `code=200`；被编辑用户自己的 at `GET /auth/me` 仍 **HTTP 200**，`qsx:auth:session:{id}` **仍在**（不清会话） |
| B6a | `DELETE /api/users/{id}`（普通用户） | HTTP 200 `code=200` |
| B6b | `GET /api/users?email=httpb6-…` | `data.total=0`（逻辑删除后分页不可见） |
| B6c | `GET /auth/me`（被删用户 at） | **HTTP 401** |
| B6d | `POST /auth/refresh`（被删用户 rt） | HTTP 200 `code=1019` |
| B6e | Redis `EXISTS qsx:auth:session:{id}` / `qsx:auth:at:{at}` | **0 / 0**（三键已清） |
| B6f | `POST /auth/register`（同一邮箱再注册） | HTTP 200 `code=200`（邮箱已释放） |

### C. 角色分配（`/api/users/{id}/roles`，Bearer 超管）

> 支撑调用：`POST /api/roles` 建角色（code=`ROLE_HTTP_xxx`）→ `PUT /api/roles/{id}/permissions` 绑 `user:page`（permId=6）→ `GET /api/permissions?pageSize=1000` 查权限 id。

| # | 操作（请求） | 返回信息 |
|---|------|------|
| C1a | `PUT /api/users/{uid}/roles` `{"roleIds":[<roleId>]}` | HTTP 200 `code=200` |
| C1b | `GET /api/users`（该用户自己的 at） | **HTTP 200**（分配 user:page 后权限即时生效） |
| C2a | `PUT /api/users/{uid}/roles` `{"roleIds":[]}`（清空） | HTTP 200 `code=200` |
| C2b | `GET /api/users`（同一用户 at） | **HTTP 403**（权限即时回收） |
| C2c | Redis `EXISTS qsx:auth:session:{uid}` | **1**（仅回收权限，不清会话） |
| C3a | `PUT /api/users/99999/roles`（用户不存在） | HTTP 200 `code=1004` |
| C3b | `PUT /api/users/{uid}/roles` `{"roleIds":[999999]}`（角色不存在） | HTTP 200 `code=1009`（且不改动原角色） |

### D. 踢人（`/api/users/{id}/kick`）

| # | 操作（请求） | 返回信息 |
|---|------|------|
| D1a | `POST /api/users/{id}/kick`（超管踢普通用户） | HTTP 200 `code=200` |
| D1b | `GET /auth/me`（被踢用户 at） | **HTTP 401** |
| D1c | `POST /auth/refresh`（被踢用户 rt） | HTTP 200 `code=1019` |
| D1d | `POST /auth/login`（被踢账号重登） | HTTP 200 `code=200`（账号未被禁用） |
| D2 | `POST /api/users/{id}/kick`（无 `user:kick` 的普通用户调用） | **HTTP 403** |

### E. 保护矩阵与 401/403 边界

| # | 操作（请求） | 返回信息 |
|---|------|------|
| E1 | `DELETE /api/users/1`（**第二个** ADMIN 用户删内置超管） | HTTP 200 `code=1025 内置超管用户不可删除` |
| E2 | `DELETE /api/users/{自己id}`（管理员删自己） | HTTP 200 `code=1022 不允许对自己执行该操作` |
| E3a | `PUT /api/users/{超管id}` `{"status":1}`（禁内置超管） | HTTP 200 `code=1020 内置超管用户不可禁用` |
| E3b | `POST /api/users/{超管id}/kick`（踢内置超管） | HTTP 200 `code=1021 内置超管用户不可强制登出` |
| E4 | `GET /api/users`（无 Authorization） | **HTTP 401** |
| E5 | `GET /api/users`（普通用户 at） | **HTTP 403** |

---

## 发现与修复

本轨道为**复核/回归性质**：用户管理与会话管理在本次真实 HTTP 中**未发现新缺陷**。

- `delete()` 保护（本次会话已补）在真实 HTTP 得到复核：删除内置超管 → **1025**、删除自己 → **1022**，与禁用（1020）、踢人（1021）保护矩阵一致。
- 与 2026-09-17 早前 test-report.md 相比，未出现行为回退；自动化 130 例与真实 HTTP 40+ 调用互相印证。

## 数据清理与校验

- 测试用户：`email LIKE '%@test.com%'` 全部清除（含逻辑删除残留），**0 残留**。
- Redis：`qsx:auth:*` 全部清除，**0 残留**。
- 种子数据：内置超管 `admin@qsx.com`（id=1, status=0）与 `ADMIN` 角色（id=1）**完好未动**。
- 应用已停止，8080 端口已释放。

---

*本报告为专项双轨测试（用户管理 + 会话管理）。自动化基线：`docs/test/test-report.md`（115 例时点版）→ 现 130 例。原始请求/响应：`docs/test/用户与会话-真实HTTP-api-results.json`。*
