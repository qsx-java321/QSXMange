# QSXManager 后台管理系统

> 单体**多模块**（5 个 Maven 模块）、前后端分离的**用户后台管理基础 demo** —— 为其他项目提供
> 可直接复用的认证、授权、用户、会话与邮箱验证码基座。

**核心特点**：

- **无 JWT**：access / refresh token 都是 32 字节随机串，**Redis 是令牌有效性的唯一真相源**——
  登出 / 踢人 / 禁用 / 删除 / 改密 / 重置密码后，旧 access token **立即失效**（不是等 30 分钟过期）。
- **ADMIN 超管不是硬编码**：通过绑定全量权限实现，与普通角色走完全相同的判定路径。
- **邮箱验证码**：注册、忘记密码、改密通道 B 三个自助流程，验证码四键 + 两条 Lua 原子脚本。
- 204 例自动化集成测试 + 230 条真实 HTTP 逐笔记录锁定行为（详见[测试报告](docs/test/项目测试报告.md)）。

## 技术栈

| 分类 | 技术 |
| :--- | :--- |
| 语言 / 框架 | Java 21 · Spring Boot 3.5.16 |
| 持久层 | MyBatis-Plus 3.5.17（分页、逻辑删除、自动填充） |
| 安全 | Spring Security + 自定义令牌过滤器 + `@PreAuthorize` 权限码 |
| 存储 | MySQL 8.4（业务）· Redis 7.4（会话 + 权限缓存 + 验证码） |
| 邮件 | Spring Mail，本地由 **Mailpit** 假 SMTP 收信（SMTP 1025 / Web UI 8025） |
| Excel | EasyExcel 4.0.3（SAX 流式，文件不落盘） |
| 其他 | BCrypt 密码、Spring AOP 审计 + `@Async` 异步落库、统一 `Result` / `PageResult` |

## 功能一览

| 模块 | 能力 |
| :--- | :--- |
| 认证中心 | 注册（需邮箱验证码）· 登录（单端，新登录覆盖旧会话）· 刷新（严格轮换）· 登出 · 当前用户 · 改密（原密码 / 验证码双通道）· 忘记密码（匿名重置） |
| 邮箱验证码 | 发送 / 校验通用服务；6 位 · 5 分钟 · 错 5 次作废 · 用后即焚 · 60 秒 1 次 · 每日 10 次；`{scene}:{email}` 双维度隔离；防枚举 |
| 会话管理 | Redis 三键（`at` / `rt` / `session`）+ 三条 Lua；单端登录、滑动续期 7 天 + 30 天绝对上限；**吊销即时生效** |
| RBAC 权限 | 角色 / 权限 / 菜单管理；权限码方法级鉴权；权限码 Redis 缓存 + 事务提交后精确失效；停用角色即时回收权限 |
| 用户管理 | 分页 / 增删改 / 启用禁用（禁用即踢下线）/ 分配角色 / 强制登出 / Excel 批量导入导出 |
| 操作日志 | AOP 包级扫描全量审计，`@Async` 异步落库；401 / 403 / 400 三处补记 |
| 统一基础设施 | `Result` 统一返回、全局异常处理（业务异常 HTTP 200 + 业务码）、分页、自动填充、逻辑删除 |

## 模块结构

```
qsx-admin  →  qsx-module-system  →  qsx-framework  →  qsx-security  →  qsx-common
（可执行）     （业务纵切）          （技术底座）       （认证内核）      （通用契约）
```

- **qsx-common**：`Result` / `ResultCode` / `BusinessException` + 审计端口契约（只依赖 lombok）
- **qsx-security**：Security 装配、令牌过滤器、双 token 会话 + Lua、权限缓存（不依赖任何业务模块）
- **qsx-framework**：MyBatis-Plus 与异步线程池配置、`PageResult`、全局异常、审计切面
- **qsx-module-system**：认证 / 验证码 / 用户 / 角色 / 权限 / 菜单 / 日志 / Excel 全部业务
- **qsx-admin**：启动类、配置、集成测试（唯一产出可执行 fat jar）

## 快速开始

### 1. 环境要求

| 组件 | 要求 |
| :--- | :--- |
| JDK / Maven | JDK 21、Maven 3.9+ |
| MySQL | 8.x Docker，`localhost:3306`，`root/123456` |
| Redis | 7.4.x Docker，`localhost:6379`（无密码）——**认证链路硬依赖**，故障即全员 401 |
| Mailpit | SMTP `localhost:1025` / Web UI `localhost:8025`（取验证码）；未启动时发码接口仍返回成功但收不到信 |

启动命令见 [docs/dev-env/组件依赖README.md](docs/dev-env/组件依赖README.md)。

### 2. 初始化数据库（**会清空既有数据**）

```bash
docker cp sql/init.sql mysql:/tmp/init.sql
docker exec mysql sh -c "mysql --default-character-set=utf8mb4 -uroot -p123456 < /tmp/init.sql"
```

脚本自包含建库 + 6 张表 + 预置数据（6 菜单 / 23 按钮权限 / ADMIN 角色 / 超管 `admin@qsx.com · admin123`）。

### 3. 启动

```bash
mvn -o -DskipTests package
java -jar qsx-admin/target/qsx-admin-1.0.0.jar      # 端口 8080
```

> 不要用 `mvn -pl qsx-admin -am spring-boot:run`：`-am` 会把其余 4 个模块也执行 `spring-boot:run`，
> 它们没有主类，直接 `BUILD FAILURE`。

### 4. 登录体验

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@qsx.com","password":"admin123"}'
```

### 5. 运行测试

```bash
mvn test                                                                 # 全量 204 例
mvn test -Dtest=SessionLuaTest -Dsurefire.failIfNoSpecifiedTests=false  # 单个类（该参数必带）
```

测试复用本地开发库与开发 Redis，**不要在本机应用正服务会话时跑**（会清掉在线会话的 Redis 键）。

## 接口速览

共 36 个接口。除匿名接口外均需请求头 `Authorization: Bearer <access token>`。

| 分组 | 接口 | 权限 |
| :--- | :--- | :--- |
| 认证 | `POST /auth/captcha` | 匿名（改密场景需登录） |
| 认证 | `POST /auth/register` | 匿名（需邮箱验证码） |
| 认证 | `POST /auth/login` · `POST /auth/refresh` | 匿名（刷新 body 只传 `refreshToken`） |
| 认证 | `POST /auth/forgot-password` | 匿名（需邮箱验证码） |
| 认证 | `POST /auth/logout` · `GET /auth/me` | 登录 |
| 认证 | `POST /auth/change-password` | 登录（原密码 / 验证码二选一） |
| 用户 | `GET /api/users` · `GET /api/users/{id}` | `user:page` · `user:get` |
| 用户 | `POST /api/users` · `PUT /api/users/{id}` · `DELETE /api/users/{id}` | `user:create` · `user:update` · `user:delete` |
| 用户 | `PUT /api/users/{id}/roles` · `POST /api/users/{id}/kick` | `user:assign-role` · `user:kick` |
| 用户 | `GET /api/users/import/template` · `POST /api/users/import` · `GET /api/users/export` | `user:import` · `user:export` |
| 角色 | `GET /api/roles` · `GET /api/roles/all` · `GET /api/roles/{id}` | `role:page` · `role:get` |
| 角色 | `POST /api/roles` · `PUT /api/roles/{id}` · `DELETE /api/roles/{id}` · `PUT /api/roles/{id}/permissions` | `role:create` · `role:update` · `role:delete` · `role:assign` |
| 权限 | `GET /api/permissions` · `/all` · `/{id}` | `perm:page` · `perm:get`（只读） |
| 菜单 | `GET /api/menus/tree` · `GET /api/menus/current` | `menu:tree` · 登录即可 |
| 菜单 | `POST /api/menus` · `PUT /api/menus/{id}` · `DELETE /api/menus/{id}` | `menu:create` · `menu:update` · `menu:delete` |
| 日志 | `GET /api/logs` · `DELETE /api/logs/{id}` · `DELETE /api/logs` | `log:page` · `log:delete` |

## 错误码

业务失败一律 **HTTP 200 + body 里的业务码**（仅 401 / 403 使用真实 HTTP 状态）。常用码：

| code | 含义 | code | 含义 |
| ---: | :--- | ---: | :--- |
| 400 | 参数校验失败 | 1019 | 刷新令牌无效或已过期 |
| 401 | 未登录 / 登录已过期 | 1020~1022 | 超管不可禁用 / 不可强登 / 不可操作自己 |
| 403 | 无操作权限 | 1023~1024 | 标识 / 角色编码创建后不可修改 |
| 1001 | 该邮箱已被注册 | 1025~1026 | 内置超管用户不可删除 / 角色不可停用 |
| 1002 | 邮箱或密码错误 | 1027 | 验证码发送过于频繁 |
| 1003 | 账号已被禁用 | 1028 | 验证码无效或已过期 |
| 1004 | 用户不存在 | 1029 | 验证码错误次数超限 |
| 1006 | 原密码错误 | 1030 | 两次输入的密码不一致 |
| 1009~1016 | 角色 / 权限 / 菜单相关 | 1031 | 改密需提供原密码或验证码之一 |
| 1017~1018 | 导入校验失败 / 数据量超限 | 1032 | 该场景验证码仅限本人邮箱 |

完整清单（含触发点与文案）见 [docs/design/](docs/design/) 的《基础设施、审计与测试》§1。

## 文档导航

| 文档 | 定位 |
| :--- | :--- |
| [docs/design/](docs/design/) | **详细设计文档**：架构与模块划分 · 认证与会话 · 授权与 RBAC · 邮箱验证码 · 业务功能与保护矩阵 · 基础设施、审计与测试 |
| [docs/test/项目测试报告.md](docs/test/项目测试报告.md) | 测试总报告：36 接口覆盖矩阵、204 例基线、230 条真实 HTTP 逐笔明细（R1~R6，含注册/登录/验证码专项轮次 §十） |
| [docs/session-notes/qsxmanager/会话总结-qsxmanager全项目.md](docs/session-notes/qsxmanager/会话总结-qsxmanager全项目.md) | 全项目主线总览（发展阶段、跨阶段决策与教训） |
| [docs/dev-env/组件依赖README.md](docs/dev-env/组件依赖README.md) | Docker 中间件清单与启停（MySQL / Redis / Mailpit 等） |
