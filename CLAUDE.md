# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 常用命令

```bash
mvn -o -DskipTests compile        # 快速编译（离线可用）
mvn spring-boot:run               # 启动，端口 8080
mvn test                          # 全量测试（115 例）
mvn test -Dtest=SessionLuaTest    # 单个测试类
mvn test -Dtest='SessionLuaTest#rotate_replayOldRefreshToken_rejected'   # 单个方法
mvn test -Dtest='RbacTest,MenuTest'                                      # 多个类必须加引号
```

**前置条件**：MySQL 8.4（`localhost:3306/QSXManager`，root/123456）与 Redis 7.4（`localhost:6379`）都在 Docker 中运行，且**两者都必须可用**——认证链路依赖 Redis，Redis 不可用时绝大部分需要登录态的用例会因 401 失败。依赖已缓存后 `-o` 离线可跑。

**测试直接复用本地开发库与开发 Redis**（没有独立测试库）。清理范围：`sys_user` 中 `email LIKE '%@test.com%'` 的测试用户 + Redis 中全部 `qsx:auth:*` 键。因此：**不要在本地应用正服务会话时跑测试**（会清掉在线会话的 Redis 键）。

初始化/重建数据库（**会清空既有数据**）：

```bash
docker cp sql/init.sql mysql:/tmp/init.sql
docker exec mysql sh -c "mysql --default-character-set=utf8mb4 -uroot -p123456 < /tmp/init.sql"
```

## 架构

分层：`web → service → mapper → domain`；`security → mapper/domain`；`common` 保持纯净。根包 `com.qsx`。

### 认证与会话（本项目最需要先读懂的部分）

**没有 JWT**。access token（AT）与 refresh token（RT）都是 32 字节 SecureRandom 的 hex 随机串，无签名无载荷，**Redis 是令牌有效性的唯一真相源**：

| Key | Value | TTL | 职责 |
| :--- | :--- | :--- | :--- |
| `qsx:auth:at:{at}` | userId | 30m | AT 有效性；**删除即吊销** |
| `qsx:auth:rt:{rt}` | userId | 7d | RT → userId；轮换时删旧立新 |
| `qsx:auth:session:{userId}` | Hash{accessToken, refreshToken, firstLoginTs} | 7d 滑动 | 单端覆盖、按用户清理、绝对上限 |

- 三个多键操作全部走 Lua 原子脚本（`resources/lua/auth_session_*.lua`），实现在 `security/session/AuthSessionServiceImpl`——**改会话行为先读这三个脚本的头部注释**，里面写明了每条约定的原因。
- 请求链路：`TokenAuthenticationFilter`（Redis 反查 userId）→ `SecurityUserDetailsService.loadUserById`（实时查库 + 权限缓存）→ `isEnabled()` → 写入 SecurityContext。
- 吊销入口统一为 `AuthSessionService.remove(userId)`，调用点：登出、踢人、禁用、删除用户、改密。**新增用户生命周期变更（如重置密码）必须一并清理会话**，否则旧令牌仍然有效。
- **fail 策略分级**：会话层 fail-closed（Redis 故障 = 拒绝认证，宁可不放行）；权限缓存 fail-open（Redis 异常降级查库）。两者边界不可混淆。

### 授权

权限码 + `@PreAuthorize`；ADMIN 超管通过**绑定全量权限**实现（不是硬编码绕过）。菜单与按钮权限共用 `sys_permission`（`type` 区分），菜单经 `parent_id` 成树。

新增权限码需要同步**三处**：`sql/init.sql` 预置（幂等 `INSERT IGNORE` + 自动绑 ADMIN）、`PermissionConstants`、控制器上的 `@PreAuthorize`。

**标识（code）与角色编码创建后不可修改**（1023/1024）：它们是鉴权依据，改错会让 `@PreAuthorize` 全线失配且无法在界面改回。角色编码还关系到内置超管保护（按 `RoleConstants.ADMIN` 匹配）。

### 权限缓存失效约定

写事务内 `publishEvent(PermissionCacheEvictEvent.ofUsers(...))` → `@TransactionalEventListener(AFTER_COMMIT)` → 删 `qsx:auth:perm:{userId}`。

**关键约束：受影响用户必须在改动关联表（`sys_user_role`/`sys_role_permission`）之前反查**，因为监听器在提交后才执行，届时关联行已被删除、反查必然为空（这曾导致「已回收的权限仍生效至 TTL 到期」）。反查方法见 `UserRoleMapper`。

### 统一返回与审计

- 所有接口返回 `Result{code,message,data}`。**业务异常返回 HTTP 200 + body 里的业务码**（`GlobalExceptionHandler`），断言要看业务码而不是 HTTP 状态。
- 操作日志由 `OperationLogAspect` 对 `com.qsx.web.controller` 包级扫描，以 `Result.code==200` 判定成功，`@Async` 落库；401 由 `RestAuthenticationEntryPoint` 补记、400 由异常处理器补记。

## 必须遵守的项目约定（都是踩过坑换来的）

- **Lua 脚本参数必须是字符串**：`StringRedisTemplate` 用 `StringRedisSerializer` 序列化参数，传 `Long`/`Integer` 会在触达 Redis 前抛 `ClassCastException`（登录全线 500）。调用侧统一 `String.valueOf(...)`，脚本内 `tonumber()`。
- **Lua 读 Hash 字段用 `HGET`，不要用 `HGETALL`**：RESP2 下后者返回的是数组而非 map，字段访问恒为 `nil`——曾导致「即时踢下线」静默失效（`remove` 永远删不掉 AT 键）。
- **Lua 写入顺序固定为「先清旧 → 写 session → 写令牌键」**：Lua 报错不回滚，这个顺序保证崩溃时留下的是死令牌（安全）而不是无法吊销的裸令牌。
- **TTL 一律整数秒且下限为 1**：`SET ... EX 0` 报错而 `EXPIRE key 0` 会直接删键。`AuthSessionProperties` 有启动期校验，纯数字配置会被当作毫秒（`30` 是 30 毫秒，不是 30 天）。
- **无事务时发布的事件会被静默丢弃**：`@TransactionalEventListener` 默认 `fallbackExecution=false`。给非 `@Transactional` 方法补发失效事件时必须同时加事务，或显式设置 fallback。
- **测试清理禁用全表删除**：`DELETE FROM sys_user` 会删掉预置超管；`userMapper.delete(null)` 更会因 `@TableLogic` 变成全表逻辑删除。只清测试用户（`email LIKE '%@test.com%'`）。
- **断言要打到机制层**：验证「吊销即时生效」这类能力时，除了断言 401，还要断言 Redis 键已消失——否则每请求查库的 `isEnabled()` 兜底会让漏实现的代码也通过测试。
- **会话脚本仅支持单节点 Redis**（无 hash tag，上集群会 CROSSSLOT）；Redis 不得改用 `allkeys-lru` 等淘汰策略（会话键被淘汰 = AT 无法吊销）。

## 测试

`BaseIntegrationTest` 提供：`register` / `loginGetToken` / `loginGetAuth`（返回 `token`/`refreshToken`/`userId`/`email`）、`postJson` / `bearerHeader` / `uniqueEmail` / `adminToken()`（走真实 `assignRoles` 链路构造超管）。用例前后自动清理测试用户与 Redis 键。

`SessionLuaTest` 是唯一直连会话层的测试（不经 HTTP），覆盖原子性、并发双花、清理完整性——**改动 Lua 脚本后必须让它全绿**。

## 文档索引

- `README.md`：技术栈、完整接口表（含权限码）、错误码表、快速开始
- `docs/design/springboot项目设计.md`：设计规格（含认证流程）
- `docs/design/用户与会话管理机制说明.md`：用户管理与会话管理的模式、流程、保护矩阵（含代码索引）
- `docs/test/test-report.md`：当前测试报告（端到端场景与修复记录）
- `docs/test/用户与会话-真实HTTP测试报告.md`：用户+会话双轨测试专项报告（Maven 130 + 真实 HTTP 逐接口）
- `docs/session-notes/qsxmanager/`：各阶段会话总结与测试报告；`会话总结-qsxmanager全项目.md` 是主线总览，**含遗留事项清单**
