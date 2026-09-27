# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 常用命令

```bash
mvn -o -DskipTests compile        # 快速编译（离线可用，5 个模块）
mvn -o -DskipTests package        # 打包（仅 qsx-admin 产出可执行 fat jar）
mvn test                          # 全量测试（204 例；仅 qsx-admin 有测试）

# 指定测试类时**必须**带 -Dsurefire.failIfNoSpecifiedTests=false：
# 多模块 reactor 会让 -Dtest 同时作用于 5 个模块，其余 4 个模块没有匹配的测试类，
# surefire 默认视为错误并直接 BUILD FAILURE（报 "No tests matching pattern ... were executed!"）
mvn test -Dtest=SessionLuaTest -Dsurefire.failIfNoSpecifiedTests=false
mvn test -Dtest='SessionLuaTest#rotate_replayOldRefreshToken_rejected' -Dsurefire.failIfNoSpecifiedTests=false
mvn test -Dtest='RbacTest,MenuTest' -Dsurefire.failIfNoSpecifiedTests=false   # 多个类必须加引号
```

启动（多模块中只有 `qsx-admin` 可执行）：

```bash
java -jar qsx-admin/target/qsx-admin-1.0.0.jar     # 先 package，再直接跑 fat jar（推荐）
# 或：mvn -o -DskipTests install && mvn -o -pl qsx-admin spring-boot:run
```

> **不要用 `mvn -pl qsx-admin -am spring-boot:run`**：`-am` 会把其余 4 个模块拉进 reactor 并对它们也执行 `spring-boot:run`，它们没有主类，会直接 `BUILD FAILURE`。

**前置条件**：MySQL 8.4（`localhost:3306/QSXManager`，root/123456）与 Redis 7.4（`localhost:6379`）都在 Docker 中运行，且**两者都必须可用**——认证链路依赖 Redis，Redis 不可用时绝大部分需要登录态的用例会因 401 失败。依赖已缓存后 `-o` 离线可跑。

**测试直接复用本地开发库与开发 Redis**（没有独立测试库）。清理范围：`sys_user` 中 `email LIKE '%@test.com%'` 的测试用户、`test-%` 前缀的测试角色/菜单（含逻辑删除行）及其两侧关联、全部 `sys_operation_log`、Redis 中全部 `qsx:auth:*` 键。因此：**不要在本地应用正服务会话时跑测试**（会清掉在线会话的 Redis 键）。

初始化/重建数据库（**会清空既有数据**）：

```bash
docker cp sql/init.sql mysql:/tmp/init.sql
docker exec mysql sh -c "mysql --default-character-set=utf8mb4 -uroot -p123456 < /tmp/init.sql"
```

## 架构

单体**多模块**（5 个 Maven 模块），根包 `com.qsx`，依赖严格单向、无环：

```
qsx-admin → qsx-module-system → qsx-framework → qsx-security → qsx-common
```

| 模块 | 职责 | 关键约束 |
| :--- | :--- | :--- |
| `qsx-common` | `Result` / `ResultCode` / `BusinessException` + 审计端口契约 `AccessLogRecorder` | 只依赖 lombok，零业务依赖 |
| `qsx-security` | Security 装配、令牌过滤器、Redis 双 token 会话 + Lua、权限缓存、`SecurityUser` / `SecurityUtils` | **不得依赖任何业务模块**；向外取数走 `port` 包（`AuthUserRepository` / `UserAuthorityRepository`），由业务模块实现 |
| `qsx-framework` | MyBatis-Plus 与异步线程池配置、`PageResult`、全局异常处理、操作日志切面 | **不得依赖任何业务模块**；审计落库走 `AccessLogRecorder` 端口 |
| `qsx-module-system` | 认证 / 验证码（Redis+Lua）/ 用户 / 角色 / 权限 / 菜单 / 日志 / Excel 全部业务 + 端口实现 | 包名与原单模块一致；`mapper/adapter` 下是端口实现（必须 `@Component`）；`service/email` 是邮件投递端口 |
| `qsx-admin` | 启动类、`application.yml`、集成测试 | 唯一可执行模块；`spring-boot-maven-plugin` 只在此声明 |

**新增业务模块**：建 `qsx-module-<域>`，pom 只依赖 `qsx-framework`，控制器沿用 `com.qsx.web.controller.<域>` 即自动纳入审计，再在 `qsx-admin` 的 pom 加一行依赖——**无需改动任何内核代码**。

**包路径是契约，不能随意挪**：`com.qsx.web.controller`（审计切点）、`com.qsx.mapper`（`@Mapper` 扫描）、`com.qsx.domain.entity`（`type-aliases-package`）三者一旦移动会静默失效（不报错，只是切面/扫描/别名全部落空）。

**单独复用认证内核**：只引 `qsx-common` + `qsx-security` 时，必须自行提供 `AuthUserRepository`、`UserAuthorityRepository`、`AccessLogRecorder` 三个 Bean，否则启动即 `NoSuchBeanDefinitionException`。

### 认证与会话（本项目最需要先读懂的部分）

**没有 JWT**。access token（AT）与 refresh token（RT）都是 32 字节 SecureRandom 的 hex 随机串，无签名无载荷，**Redis 是令牌有效性的唯一真相源**：

| Key | Value | TTL | 职责 |
| :--- | :--- | :--- | :--- |
| `qsx:auth:at:{at}` | userId | 30m | AT 有效性；**删除即吊销** |
| `qsx:auth:rt:{rt}` | userId | 7d | RT → userId；轮换时删旧立新 |
| `qsx:auth:session:{userId}` | Hash{accessToken, refreshToken, firstLoginTs} | 7d 滑动 | 单端覆盖、按用户清理、绝对上限 |

- 三个多键操作全部走 Lua 原子脚本（`qsx-security/src/main/resources/lua/auth_session_*.lua`，**脚本必须与 `AuthSessionServiceImpl` 同模块**，否则 `ClassPathResource` 加载失败），实现在 `qsx-security` 的 `security/session/AuthSessionServiceImpl`——**改会话行为先读这三个脚本的头部注释**，里面写明了每条约定的原因。
- 请求链路：`TokenAuthenticationFilter`（Redis 反查 userId）→ `SecurityUserDetailsService.loadUserById`（实时查库 + 权限缓存）→ `isEnabled()` → 写入 SecurityContext。
- 吊销入口统一为 `AuthSessionService.remove(userId)`，调用点：登出、踢人、禁用、删除用户、改密。**新增任何涉及账号状态或凭据的用户生命周期变更，必须一并清理会话**，否则旧令牌仍然有效。
- **fail 策略分级**：会话层 fail-closed（Redis 故障 = 拒绝认证，宁可不放行）；权限缓存 fail-open（Redis 异常降级查库）。两者边界不可混淆。

### 邮箱验证码

`POST /auth/captcha`（匿名放行）+ 两条 Lua（`qsx-module-system/src/main/resources/lua/captcha_*.lua`，**脚本必须与使用方同模块**，否则 `ClassPathResource` 加载失败）。

| Key | 含义 | TTL |
| :--- | :--- | :--- |
| `qsx:auth:cap:{scene}:{email}` | 6 位验证码（明文） | 5m |
| `qsx:auth:cap:attempt:{scene}:{email}` | 错误计数，达到 max-attempts 即作废 | 与码同生共死 |
| `qsx:auth:cap:limit:{scene}:{email}` | 发送间隔标记（`SET NX`） | 60s |
| `qsx:auth:cap:daily:{yyyyMMdd}:{scene}:{email}` | 当日发送计数 | 24h（**日期串键**） |

- **发送侧也必须原子**：限流标记 + 当日计数 + 落码 + 清零错次写在同一条 Lua 里。拆成 Java 侧 `INCR` + `EXPIRE` 会有两个静默故障——中途失败留下**无 TTL** 的键，把该 `{scene}:{email}` 永久锁死；每次发送都续期则日限退化成滑动窗口、永不触发。
- 校验侧 `GET → 比对 → 计数 → 删除` 必须原子，否则同一张码可被并发双花（「用后即焚」失效）。
- 投递走 `EmailService` 端口（`service/email`）：`SmtpEmailService`（本地 Mailpit 与线上真实邮箱**共用同一份**，差异只在 `spring.mail.*`）与 `DebugEmailService`（`qsx.captcha.debug=true` 时响应直返码）。**两个实现条件装配必须互斥**，都装配会启动即 `NoUniqueBeanDefinitionException`。`@Async("captchaMailExecutor")` 靠外部 Bean 调用触发代理。
- 场景语义：`REGISTER` 要求邮箱未注册；`FORGOT_PASSWORD` 对未注册邮箱**不落码不投递、但响应与成功完全一致**（防枚举）；`CHANGE_PASSWORD` 要求登录且邮箱为本人（URL 层 permitAll，靠业务层 `SecurityUtils` 判定，未登录返回 body 业务码 401）。
- **校验顺序统一为「前置条件 → 验证码」**（注册是「唯一性 → 验证码」）：校验成功即用后即焚，先校验会把用户手里那张有效的码烧掉。

### 授权

权限码 + `@PreAuthorize`；ADMIN 超管通过**绑定全量权限**实现（不是硬编码绕过）。菜单与按钮权限共用 `sys_permission`（`type` 区分），菜单经 `parent_id` 成树。

新增权限码需要同步**三处**：`sql/init.sql` 预置（幂等 `INSERT IGNORE` + 自动绑 ADMIN）、`PermissionConstants`、控制器上的 `@PreAuthorize`。

**标识（code）与角色编码创建后不可修改**（1023/1024）：它们是鉴权依据，改错会让 `@PreAuthorize` 全线失配且无法在界面改回。角色编码还关系到内置超管保护（按 `RoleConstants.ADMIN` 匹配）。

### 权限缓存失效约定

写事务内 `publishEvent(PermissionCacheEvictEvent.ofUsers(...))` → `@TransactionalEventListener(AFTER_COMMIT)` → 删 `qsx:auth:perm:{userId}`。

**关键约束：受影响用户必须在改动关联表（`sys_user_role`/`sys_role_permission`）之前反查**，因为监听器在提交后才执行，届时关联行已被删除、反查必然为空（这曾导致「已回收的权限仍生效至 TTL 到期」）。反查方法见 `UserRoleMapper`。

### 统一返回与审计

- 所有接口返回 `Result{code,message,data}`。**业务异常返回 HTTP 200 + body 里的业务码**（`GlobalExceptionHandler`），断言要看业务码而不是 HTTP 状态。
- 操作日志由 `OperationLogAspect`（`qsx-framework`）对 `com.qsx.web.controller` 包级扫描，以 `Result.code==200` 判定成功，经 `AccessLogRecorder` 端口异步落库；401 由 `RestAuthenticationEntryPoint` 补记、400 由异常处理器补记。
- **`@Async` 不得自调用**：`OperationLogServiceImpl.record()` 上的 `@Async` 靠 Spring 代理生效，若改成同类内部调用（如再包一层 `this.xxx()`）会静默失效——日志落库退化为同步、主请求被数据库写入阻塞，且**没有编译错误**。

## 必须遵守的项目约定（都是踩过坑换来的）

- **Lua 脚本参数必须是字符串**：`StringRedisTemplate` 用 `StringRedisSerializer` 序列化参数，传 `Long`/`Integer` 会在触达 Redis 前抛 `ClassCastException`（登录全线 500）。调用侧统一 `String.valueOf(...)`，脚本内 `tonumber()`。
- **Lua 读 Hash 字段用 `HGET`，不要用 `HGETALL`**：RESP2 下后者返回的是数组而非 map，字段访问恒为 `nil`——曾导致「即时踢下线」静默失效（`remove` 永远删不掉 AT 键）。
- **Lua 写入顺序固定为「先清旧 → 写 session → 写令牌键」**：Lua 报错不回滚，这个顺序保证崩溃时留下的是死令牌（安全）而不是无法吊销的裸令牌。
- **TTL 一律整数秒且下限为 1**：`SET ... EX 0` 报错而 `EXPIRE key 0` 会直接删键。`AuthSessionProperties` 有启动期校验，纯数字配置会被当作毫秒（`30` 是 30 毫秒，不是 30 天）。
- **限流计数必须原子，且 TTL 只能在首次设置**：`INCR` 与 `EXPIRE` 分开写，中断就会留下没有 TTL 的键（该 key 永久锁死）；每次都 `EXPIRE` 则窗口滑动、上限永不触发。验证码四键的做法见 `captcha_send.lua`（日期串键 + 仅计数为 1 时设 TTL）。
- **无事务时发布的事件会被静默丢弃**：`@TransactionalEventListener` 默认 `fallbackExecution=false`。给非 `@Transactional` 方法补发失效事件时必须同时加事务，或显式设置 fallback。
- **测试清理禁用全表删除**：`DELETE FROM sys_user` 会删掉预置超管；`userMapper.delete(null)` 更会因 `@TableLogic` 变成全表逻辑删除。只清测试用户（`email LIKE '%@test.com%'`）与 `test-` 前缀的测试角色/菜单。
- **物理唯一索引 + 逻辑删除 ⇒ 判重必须含已删行**：`uk_role_code` / `uk_perm_code` 都是**不含 `deleted` 列**的唯一索引，而删除是逻辑删除——用 `BaseMapper.selectOne` 判重会被 `@TableLogic` 自动过滤掉已删行，于是「删除后用同一编码新建」通过校验、在 INSERT 时撞唯一索引返回 **500 而非 1010/1016**。角色与权限走 `countByCodeIncludeDeleted`（显式 SQL 绕开过滤）；用户则用「删除时把 `email` 改写为 `#deleted_<时间戳>`」释放索引。
- **断言要打到机制层**：验证「吊销即时生效」这类能力时，除了断言 401，还要断言 Redis 键已消失——否则每请求查库的 `isEnabled()` 兜底会让漏实现的代码也通过测试。
- **会话脚本仅支持单节点 Redis**（无 hash tag，上集群会 CROSSSLOT）；Redis 不得改用 `allkeys-lru` 等淘汰策略（会话键被淘汰 = AT 无法吊销）。

## 测试

`BaseIntegrationTest` 提供：`register`（**内部走真实发码链路**：调 `/auth/captcha` → 从 Redis 取码 → 带码注册，故各测试类的 `register(...)` 调用点无需关心验证码）/ `loginGetToken` / `loginGetAuth`（返回 `token`/`refreshToken`/`userId`/`email`）、`postJson` / `bearerHeader` / `uniqueEmail` / `adminToken()`（走真实 `assignRoles` 链路构造超管）。用例前后自动清理测试用户、测试角色（`test-` 前缀，含逻辑删除行）、操作日志表，并清空 Redis 会话与权限缓存键。

`SessionLuaTest` 是唯一直连会话层的测试（不经 HTTP），覆盖原子性、并发双花、清理完整性——**改动 Lua 脚本后必须让它全绿**。

**36 个接口 ↔ 测试类的对应关系见 `docs/test/项目测试报告.md` §1.4 覆盖矩阵（验证码轮次见 §九）。** 新增接口须同步补测试：鉴权正反向、业务码矩阵、边界（401/403、越权、不存在）、以及机制层断言（该落库的落库、该清的键要清）。全部测试类：认证会话 `AuthControllerTest` / `AuthRefreshTest` / `AuthSessionTest` / `SecurityAccessTest` / `SessionLuaTest`，用户 `UserControllerTest` / `UserEdgeTest` / `UserKickTest` / `UserImportExportTest`，角色权限 `RoleTest` / `PermissionTest` / `RbacTest` / `RbacCacheTest` / `RbacCacheDisabledTest` / `AdminProtectionTest`，菜单日志 `MenuTest` / `LogTest`，邮箱验证码 `CaptchaTest` / `ForgotPasswordTest` / `ChangePasswordChannelTest` / `CaptchaMailSmokeTest`（Mailpit 未启动时 `assumeTrue` 跳过），改造回归 `RefactorRegressionTest`，全链路 `BusinessFlowTest`。

## 文档索引

- `README.md`：技术栈、完整接口表（含权限码）、错误码表、快速开始
- `docs/design/springboot项目设计.md`：设计规格（架构/模块划分、认证流程、数据表、注意事项）
- `docs/design/验证码功能实施方案.md`：**当前实施依据**（定稿待批，2026-09-27 逐条核对代码后重写，含 Mailpit + @Async 投递、Lua 原子限流、测试取码方式与阶段验收）
- `docs/design/验证码功能改造计划方案.md`：旧参考稿（**已被上者取代**，仅历史留存）
- `docs/test/项目测试报告.md`：**全项目测试总报告**（五轮合并：36 接口覆盖矩阵、204 例自动化基线、191 条真实 HTTP 逐接口明细、累计 16 处产品缺陷与 7 项观察项、环境清理验收）；验证码轮次见 §九，原始请求/响应见同目录 `项目测试报告.json`
- `docs/session-notes/qsxmanager/`：`会话总结-qsxmanager全项目.md` 是主线总览（含设计约束与有意取舍）；早期阶段增量总结已合并归档删除，早期测试细节已并入 `docs/test/项目测试报告.md`
