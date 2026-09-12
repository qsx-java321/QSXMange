# QSXManager · 操作日志模块 穿透式测试报告

> 测试时间：2026-09-11
> 方式：重置预置库 → 启动应用(8080) → curl 真实 HTTP 请求 → 逐场景比对 `sys_operation_log` 落库
> 目标：验证「谁访问了什么 URL、成功/失败、库里存了什么、log.info 打印了什么」四者一一对应

## 一、环境与数据基线

- 数据库：`QSXManager`（MySQL 8.4，`mysql` 容器），测试前重跑 `sql/init.sql` 重置到干净预置态。
- 应用：`mvn spring-boot:run`（端口 8080），日志输出 `target/manual-app.log`。

### 重置后基线快照
| 对象 | 内容 |
|---|---|
| 用户 | `admin@qsx.com`（id=1, deleted=0）唯一 |
| 角色 | `ADMIN`（id=1）唯一 |
| ADMIN 绑定日志权限 | `log:page` + `log:delete` ✅ |
| 用户-角色 | id=1 → ADMIN ✅ |
| `sys_operation_log` | **0 条** |

### 日志相关预置权限
`system-log(24, MENU)` → `log:page(25)`、`log:delete(26)`，均挂 `system` 菜单下。

## 二、场景结果汇总

| ID | 场景 | 请求 | HTTP | Body code | 落库日志 | 结论 |
|---|---|---|---|---|---|---|
| C1 | 匿名注册成功 | POST /auth/register | 200 | 200 | success=1,user空 | ✅ |
| C2 | 管理员登录 | POST /auth/login | 200 | 200 | success=1,user空 | ✅ |
| C3 | 业务成功(带JWT) | GET /api/roles/all | 200 | 200 | success=1,user=admin | ✅ |
| C4 | 业务成功(带query) | GET /api/users?pageNum=1&pageSize=2 | 200 | 200 | success=1,user=admin | ✅ |
| C5 | 业务失败-登录错密码 | POST /auth/login | 200 | 1002 | success=0,http=200,err=邮箱或密码错误 | ✅ |
| C6 | 业务失败-重复注册 | POST /auth/register | 200 | 1001 | success=0,http=200,err=该邮箱已被注册 | ✅ |
| C7 | 未登录 | GET /api/users(无token) | 401 | 401 | success=0,http=401,err=未登录或登录已过期 | ✅(EntryPoint补记) |
| C8 | 无效token | GET /api/users | 401 | 401 | success=0,http=401 | ✅ |
| C9 | 越权(普通用户) | GET /api/users | 403 | 403 | success=0,http=403,err=无操作权限,user=normal | ✅(切面捕获) |
| C10 | 参数校验失败 | POST /api/users(缺email) | 200 | 400 | success=0, http=400, err=密码不能为空, user=admin | ✅ (GlobalExceptionHandler补记) |
| C11 | 日志查询+自排除 | GET /api/logs?pageSize=5 | 200 | 200 | total 前后一致，本请求未记录 | ✅ |
| C12 | 日志筛选 | GET /api/logs?url=roles | 200 | 200 | 精确命中 /api/roles/all | ✅ |
| C13 | 删除单条+自排除 | DELETE /api/logs/12 | 200 | 200 | id12 删除，无新增日志 | ✅ |
| C14 | 清空+自排除 | DELETE /api/logs | 200 | 200 | total=0，无新增日志 | ✅ |
| C15 | log.info 打印 | 抓应用日志 | - | - | 与落库逐字段一致 | ✅ |

## 三、逐场景详细记录

### C1 匿名注册成功
- 请求：`POST /auth/register` body `{"email":"logtest1@example.com","password":"pass123"}`
- 返回：`HTTP 200` → `{"code":200,"message":"操作成功","data":null}`
- 落库：`id=2 {user_id:NULL, username:NULL, POST, /auth/register, http:200, success:1, err:NULL, cost:252ms}`
- 结论：匿名接口成功记录，user 为空（未认证身份）✅

### C2 管理员登录
- 请求：`POST /auth/login` `{"admin@qsx.com/admin123}`
- 返回：`HTTP 200`，`data.token`（JWT，HS384），`permissions` 含 `system-log/log:page/log:delete`（ADMIN 日志权限生效）
- 落库：`id=3 {NULL, POST, /auth/login, http:200, success:1, cost:512ms}`
- 结论：登录请求当时仍是匿名，user 为空；权限载荷正确 ✅

### C3 业务成功（带 JWT）
- 请求：`GET /api/roles/all`，Header `Authorization: Bearer <token>`
- 返回：`HTTP 200`，roles 列表
- 落库：`id=4 {user_id:1, admin@qsx.com, GET, /api/roles/all, http:200, success:1, cost:68ms}`
- 结论：登录后 user 正确填充为 admin ✅

### C4 业务成功（含 query）
- 请求：`GET /api/users?pageNum=1&pageSize=2`
- 返回：`HTTP 200` 分页
- 落库：`id=5 {user_id:1, admin@qsx.com, GET, /api/users?pageNum=1&pageSize=2, http:200, success:1, cost:190ms}`
- 结论：URL 含 query 完整记录 ✅

### C5 业务失败（登录密码错误 1002）
- 请求：`POST /auth/login` 错密码
- 返回：`HTTP 200` → `{"code":1002,"message":"邮箱或密码错误"}`
- 落库：`id=6 {NULL, POST, /auth/login, http:200, success:0, err:邮箱或密码错误}`
- 结论：**业务失败 = HTTP 200 + success=0 + 记录业务码消息**，本项目特征，判定正确 ✅

### C6 业务失败（重复注册 1001）
- 请求：`POST /auth/register` 已存在邮箱
- 返回：`HTTP 200` → `{"code":1001,"message":"该邮箱已被注册"}`
- 落库：`id=7 {NULL, POST, /auth/register, http:200, success:0, err:该邮箱已被注册}`
- 结论：同上 ✅

### C7 未登录（401）—— Security 层补记
- 请求：`GET /api/users`（无 token）
- 返回：`HTTP 401` → `{"code":401,"message":"未登录或登录已过期"}`
- 落库：`id=8 {NULL, GET, /api/users, http:401, success:0, err:未登录或登录已过期, cost:0}`
- 结论：未登录不进 Controller，由 `RestAuthenticationEntryPoint` 异步补记，http_status=401 ✅

### C8 无效 token（401）
- 请求：`GET /api/users`，Header `Authorization: Bearer invalid.token.xxx`
- 返回：`HTTP 401`
- 落库：`id=9 {NULL, GET, /api/users, http:401, success:0, err:未登录或登录已过期}`
- 结论：签名/格式非法静默作未认证，补记 401 ✅

### C9 越权（403）—— 切面捕获 `@PreAuthorize`
- 前置：注册普通用户 `normal1@example.com`（无角色）
- 请求：`GET /api/users`，Bearer=normal1
- 返回：`HTTP 403` → `{"code":403,"message":"没有操作权限"}`
- 落库：`id=12 {user_id:3, normal1@example.com, GET, /api/users, http:403, success:0, err:无操作权限, cost:1ms}`
- 结论：因切面 `@Order(HIGHEST_PRECEDENCE)` 外层，能捕获 `@PreAuthorize` 抛出的 `AccessDeniedException`，**403 成功记录且带对应用户** ✅

### C10 参数校验失败（400）—— ✅ 已修复
- 请求：`POST /api/users` body `{"status":0}` 缺 email/password，Bearer=admin
- 返回：`HTTP 200` → `{"code":400,"message":"密码不能为空"}`
- 落库：`id=3 {user_id:1, admin@qsx.com, POST, /api/users, http:400, success:0, error_msg:密码不能为空, cost:0}`
- 结论：**已修复**——400 校验失败现可完整记录。
  - 根因：`@Valid` 校验在 AOP 切入点**之前**的参数解析层（`HandlerMethodArgumentResolver`）抛 `MethodArgumentNotValidException`，`@Around` 的 `proceed()` 未执行，切面记录不到。
  - 方案：将 400 失败补记下沉到 `GlobalExceptionHandler.handleValidException`（该校验失败的实际必经点），通过 `RequestContextHolder` 取 method/url、`SecurityContextHolder` 取操作人，`@Async` 异步落库；同时移除切面中不可达的 `MethodArgumentNotValidException` 分支，消除死代码并避免潜在双记。
  - `error_msg` 取校验器具体消息（与返回 body 一致，如"密码不能为空"）。

### C11 日志查询接口 + 自排除
- 请求：`GET /api/logs?pageNum=1&pageSize=5`，Bearer=admin
- 返回：`HTTP 200` 分页，records 字段完整（userId/username/method/url/httpStatus/success/errorMsg/costMs/createTime，snake↔camel 映射正确），total=11
- 自排除：查询前 total=11，查询后 total=11 → **本查询未被记录** ✅

### C12 日志筛选
- 请求：`GET /api/logs?url=roles`
- 返回：精确命中 `id=4 /api/roles/all`（total=1）✅

### C13 删除单条 + 自排除
- 请求：`DELETE /api/logs/12`
- 返回：`HTTP 200`
- 删除前 total=11 → 后 total=10；`WHERE id=12` 计数=0（已删除）；删除请求本身未新增日志（10 = 11−1+0）✅

### C14 清空 + 自排除
- 请求：`DELETE /api/logs`
- 返回：`HTTP 200`
- 清空后 total=0；清空请求自身被 `/api/logs` 前缀排除，未产生新日志 ✅

### C15 log.info 打印一致性
应用控制台 `manual-app.log` 样例（logger=`com.qsx.aspect.OperationLogAspect`）：
```
INFO OperationLogAspect : 操作日志: user=admin@qsx.com GET /api/users?pageNum=1&pageSize=2 http=200 success=1 cost=190ms err=null
INFO OperationLogAspect : 操作日志: user=null POST /auth/login http=200 success=0 cost=71ms err=邮箱或密码错误
INFO OperationLogAspect : 操作日志: user=normal1@example.com GET /api/users http=403 success=0 cost=1ms err=无操作权限
```
与对应落库记录（C3/C5/C9）逐字段一致 ✅

## 四、测试发现 / 缺陷说明

| 级别 | 现象 | 分析 | 建议 |
|---|---|---|---|
| ✅ 已解决 | `@Valid` 参数校验失败(400)原本不记录日志 | 校验在 AOP 切入点之前的参数解析层抛异常，切面 `proceed()` 未执行 | 已改为由 `GlobalExceptionHandler.handleValidException` 统一补记 400 失败日志，并移除切面不可达分支 |
| ℹ️ 期望一致 | 未登录(401)由 EntryPoint 补记，`cost_ms=0` | 非 Controller 调用，无耗时统计 | 符合设计，若需 401 耗时可改为在 EntryPoint 计时 |

## 五、结论

操作日志模块**核心链路全部符合预期**：
- 认证明细（匿名成功、登录、业务成功/失败、401、403）均正确落库，字段逐一对应；
- 「成功/失败」以业务语义判定准确（HTTP 200 也可为失败，如 1002/1001）；
- `log.info` 打印与落库记录一致；
- 日志管理接口（分页/筛选/删除/清空）正常，且 `/api/logs` 自身请求被排除（自排除不产生新日志）——避免日志自膨胀。

唯一注意点（C10）即参数校验(400)盲区，**已通过 `GlobalExceptionHandler` 补记修复并通过真实测试验证**，现 400 校验失败同样可完整落库审计。