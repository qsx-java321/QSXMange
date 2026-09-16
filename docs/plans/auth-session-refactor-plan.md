# QSXManager 双 Token 会话机制改造 - 实施计划方案

> 状态：待实施
> 前置文档：`docs/plans/auth-session-refactor-requirements.md`（需求与决策，已确认）
> 基线代码：已提交的最新版本（含 refresh token 会话管理，2026-09-13）

## 1. 目标 Redis 模型

| Key | Value | TTL | 职责 |
| :--- | :--- | :--- | :--- |
| `qsx:auth:at:{accessToken}` | userId(string) | 30min | AT 有效性判定与身份来源，删除即吊销 |
| `qsx:auth:rt:{refreshToken}` | userId(string) | 7d | RT→userId 映射；轮换时删旧立新 |
| `qsx:auth:session:{userId}` | Hash{accessToken, refreshToken, firstLoginTs} | 7d（滑动） | 会话索引：单端覆盖、按用户清理、绝对上限 |

凭证形态：AT/RT 均为 32 字节 SecureRandom hex（64 字符），无签名无载荷。废除 jjwt。

核心行为变化：

1. **AT 可主动吊销**：删 `at` key 即瞬间踢下线（现方案踢人只删 refresh，旧 AT 可用满 30 分钟）。
2. **refresh 不需要 userId**：由 rt key 反查，`RefreshRequest` 简化为 `{refreshToken}`。
3. **email 与 token 解耦**：随机串不含载荷，改邮箱不再影响旧 token。
4. **废除** `qsx:auth:refresh:{userId}` 哈希方案及其 3 个类。

## 2. 核心数据流

### 2.1 登录 `POST /auth/login`

沿用 `AuthenticationManager` 认证（含禁用检查），随后 Lua 原子执行：

```lua
-- issue(at, rt, userId)
SET  qsx:auth:at:{newAt}  userId EX 30m
SET  qsx:auth:rt:{newRt}  userId EX 7d
local old = HGETALL qsx:auth:session:{userId}
if old.accessToken then DEL qsx:auth:at:{old.accessToken} end   -- 单端：旧端立即失效
if old.refreshToken then DEL qsx:auth:rt:{old.refreshToken} end
HSET qsx:auth:session:{userId} accessToken=newAt refreshToken=newRt firstLoginTs=now
EXPIRE qsx:auth:session:{userId} 7d
```

LoginVO 字段名不变（token / refreshToken / userId / email / nickname / roles / permissions），前端无感。

### 2.2 每请求认证（TokenAuthenticationFilter）

原 `JwtAuthenticationFilter` 重写：

```java
// 1. 取 Authorization: Bearer {at}
// 2. userId = GET qsx:auth:at:{at}；null → 保持未认证（EntryPoint 统一 401）
// 3. request.setAttribute(TOKEN_ATTR, at)          // 供 logout 复用
// 4. SecurityUser user = userDetailsService.loadUserById(userId)   // 实时查库 + qsx:auth:perm: 权限缓存
// 5. user.isEnabled() 通过则写 SecurityContext
```

**取舍说明**：AT 为随机串，Redis 之外无法解析身份，因此会话层 **fail-closed**（Redis 故障 = 全员 401，无降级查库路径）；权限缓存层保持 **fail-open**（降级查库），两级策略边界清晰。

### 2.3 刷新 `POST /auth/refresh`（入参 `{refreshToken}`）

```lua
-- rotate(rawRt, newAt, newRt, maxLifetimeMs)
local userId = GET qsx:auth:rt:{rawRt}
if not userId then return 'INVALID' end
local cur = HGET qsx:auth:session:{userId} refreshToken
if cur ~= rawRt then return 'INVALID' end              -- 双保险：拦已轮换/已被覆盖的旧 rt
local firstLoginTs = HGET qsx:auth:session:{userId} firstLoginTs
if now - firstLoginTs > maxLifetimeMs then
    DEL at/rt/session 三键；return 'EXPIRED'             -- 30d 绝对上限，强制重登
end
local oldAt = HGET qsx:auth:session:{userId} accessToken
DEL qsx:auth:rt:{rawRt}                                 -- 轮换：旧 rt 立即失效
DEL qsx:auth:at:{oldAt}
SET qsx:auth:at:{newAt} userId EX 30m
SET qsx:auth:rt:{newRt} userId EX 7d
HSET qsx:auth:session:{userId} accessToken=newAt refreshToken=newRt    -- firstLoginTs 保留
EXPIRE qsx:auth:session:{userId} 7d                     -- 滑动续期
return 'OK'
```

Service 层前置防线（保留现有语义）：查库确认用户存在且 `status=0`，否则 1019。Lua 返回 INVALID/EXPIRED 统一映射 1019 REFRESH_TOKEN_INVALID，Redis 异常同样 1019（fail-closed）。

### 2.4 登出 / 踢人 / 禁用 / 删除

统一入口 `AuthSessionService.remove(userId)`，Lua：

```lua
local s = HGETALL qsx:auth:session:{userId}
if s.accessToken then DEL qsx:auth:at:{s.accessToken} end
if s.refreshToken then DEL qsx:auth:rt:{s.refreshToken} end
DEL qsx:auth:session:{userId}
```

| 场景 | 触发点 | Redis 异常策略 |
| :--- | :--- | :--- |
| 登出 | `AuthServiceImpl.logout`（自身 userId） | 降级为成功（现状不变） |
| 踢人 | `UserServiceImpl.kick`（保护规则 1021/1022 不变） | fail-closed 抛 500（现状不变） |
| 禁用 | `UserServiceImpl.update`（status=1 分支新增，保护规则 1020/1022 不变） | 仅告警不阻断（禁用优先，查库 isEnabled 兜底） |
| 删除 | `UserServiceImpl.delete`（替换原 refreshTokenService.remove） | 仅告警不阻断（现状不变） |

## 3. 文件级改造清单

### 3.1 新增（8 个）

| 文件 | 职责 |
| :--- | :--- |
| `com.qsx.security.session.AuthSessionService` | 接口：`issue(userId)` / `rotate(rawRt)` / `remove(userId)` / `findUserIdByAccessToken(at)` |
| `com.qsx.security.session.AuthSessionServiceImpl` | 实现：三个 Lua 脚本调用 + fail-closed 异常策略 |
| `com.qsx.security.model.AuthSession` | Hash 载体：accessToken / refreshToken / firstLoginTs |
| `com.qsx.security.constant.AuthRedisKeys` | 三前缀常量 + `at()/rt()/session()` key 构造器 |
| `com.qsx.config.properties.AuthSessionProperties` | `qsx.auth.session`：at-ttl / rt-ttl / max-lifetime（Duration 绑定） |
| `com.qsx.config.AuthSessionLuaConfig` | 三个 `DefaultRedisScript` Bean |
| `resources/lua/auth_session_issue.lua` | 登录签发（2.1） |
| `resources/lua/auth_session_rotate.lua` | 刷新轮换（2.3） |
| `resources/lua/auth_session_remove.lua` | 会话清理（2.4） |

> `AuthSessionService` 返回 `AuthSession`（含明文 at/rt），仅内部传递；原始 RT 明文作 key 属已确认设计（需求 D3）。

### 3.2 修改（9 个）

| 文件 | 改动 |
| :--- | :--- |
| `JwtTokenProvider` → `TokenProvider` | 删 JWT 生成/解析/验签；保留 `generateRefreshToken()`（32B hex），新增 `generateAccessToken()`（同构） |
| `JwtAuthenticationFilter` → `TokenAuthenticationFilter` | 见 2.2；Redis 异常保持未认证 + ERROR 日志 |
| `SecurityUserDetailsService` | 新增 `loadUserById(Long)`（userId 来自 Redis，替代 email 入口做每请求加载） |
| `AuthServiceImpl` | login/logout 改调 `AuthSessionService`；refresh 去 userId 入参、调 `rotate(rawRt)` |
| `RefreshRequest` | 改为 `{refreshToken}` 单字段（@NotBlank） |
| `UserServiceImpl` | delete/kick 换 `authSessionService.remove`；update 禁用分支追加会话清理 |
| `SecurityConfig` | 过滤器替换（白名单 `/auth/login`、`/auth/register`、`/auth/refresh` 不变） |
| `application.yml` | 删 `jwt.*` 块 → 新增 `qsx.auth.session`（见第 5 节） |
| `pom.xml` | 删 jjwt-api/jjwt-impl/jjwt-jackson 依赖与 `jjwt.version` 属性 |

### 3.3 删除（4 个）

`RefreshTokenServiceImpl`、`RefreshTokenService`、`RefreshSession`、`JwtProperties`（被 `AuthSessionProperties` 取代）。

### 3.4 明确不动

权限缓存全链路（`qsx:auth:perm:`、失效事件、fail-open）、`ResultCode`（复用 1019）、`operationLogAspect`、RBAC 接口、`sql/init.sql`、数据库结构。

## 4. 安全设计要点

1. **Lua 原子性**：单端覆盖、轮换、清理均为原子脚本，消除「并发登录旧 at 残留」「刷新与踢人竞态」。
2. **RT 轮换 + 重放防护**：轮换后旧 rt key 立即删除，重放固定 1019；session 双保险比对拦截已被新登录覆盖的旧 rt。
3. **明文 rt 作 key 的拖库风险**（已确认设计）：泄露 Redis 者可直接持 rt 换新 token。缓解：网络隔离、Redis ACL 最小权限、登录行为监控；未来收紧方案收敛在 `AuthSessionServiceImpl` 单类内（key 换 SHA-256），不影响其他模块。
4. **fail 策略分级**：会话层 fail-closed（认证宁可不可用，不可错误放行）；权限缓存 fail-open（业务可用性优先）。策略写入类注释固化。
5. **30 天绝对上限**：`firstLoginTs` 轮换不重置，仅新登录刷新，防 rt 无限滚动。
6. **禁用/删除双保险**：会话清理（立即失效）+ 每请求查库 `isEnabled`（清理失败兜底）。

## 5. 配置变更

```yaml
# application.yml：删除 jwt.* 块，新增
qsx:
  auth:
    session:
      at-ttl: 30m        # access token
      rt-ttl: 7d         # refresh token + session Hash（滑动）
      max-lifetime: 30d  # 自首次登录起的会话绝对上限
```

删除项：`jwt.secret / expiration / refresh-expiration / refresh-max-lifetime / header / prefix`（`header/prefix` 本就与 SecurityConstants 冗余）。

## 6. 测试改造计划

1. `BaseIntegrationTest`：`REFRESH_SESSION_KEY_PREFIX` 替换为 `qsx:auth:at:/rt:/session:` 三前缀清理；`loginGetAuth()` 响应字段名不变，无需改。
2. `AuthRefreshTest`：适配新入参 `{refreshToken}`；新增——轮换后旧 rt 重放 1019、rt 不存在 1019、rt 与 session 不匹配 1019。
3. `UserKickTest`：行为升级——踢人后旧 AT 立即访问受保护接口断言 401（旧方案做不到）。
4. 新增 `AuthSessionTest`：单端覆盖（二次登录后旧 at/rt 全部失效）、登出后 AT 失效、禁用后 AT 失效、删除用户后 AT 失效、改邮箱后 AT 仍可用。
5. `RbacCacheTest / SecurityAccessTest / BusinessFlowTest` 等全量回归，`mvn test` 全绿（85+ 基线）。
6. 真实 HTTP 端到端验证，报告落盘 `docs/test/`（逐接口「请求信息 → 响应 JSON」既定格式）。

## 7. 实施里程碑（每步可独立提交）

| 阶段 | 内容 | 验证 |
| :--- | :--- | :--- |
| M1 | 会话领域层：AuthRedisKeys / AuthSessionProperties / AuthSession / AuthSessionService + 3 Lua + LuaConfig | 提供 redis-cli --eval 手测脚本验收 Lua 行为 |
| M2 | Token 生成与过滤器：TokenProvider、TokenAuthenticationFilter、loadUserById、SecurityConfig、pom 删 jjwt | 登录 → 携带新 AT 访问 /auth/me |
| M3 | 认证接口：login/refresh/logout 改造、RefreshRequest、删除 RefreshTokenServiceImpl 等 4 类 | AuthControllerTest / AuthRefreshTest |
| M4 | 用户管理联动：delete / kick / update(禁用) 清会话 | UserKickTest + 新 AuthSessionTest 用例 |
| M5 | 全量测试回归 + 真实 HTTP 端到端 + 测试报告落盘 | 全绿 |
| M6 | 更新 README 与会话机制设计文档，提交推送 | 工作区干净 |

## 8. 兼容性、迁移与风险

1. **上线即全员重登**：旧 JWT 与新校验不兼容；旧 `qsx:auth:refresh:{userId}` 键残留，上线时一次性 `SCAN qsx:auth:refresh:*` 清理（临时 CommandLineRunner，上线后移除）。
2. **前端联调点仅一处**：refresh 请求体 `{userId, refreshToken}` → `{refreshToken}`；AT 对前端仍是字符串，无感。
3. **Redis 升级为认证链路硬依赖**：故障即全员 401。当前已开 `appendonly yes`；建议补监控告警，如需高可用可后续引入 Sentinel。
4. **数据库零变更**，`init.sql` 不动。