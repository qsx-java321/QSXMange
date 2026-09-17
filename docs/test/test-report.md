# QSXManager 双 Token 会话机制改造 — 测试报告

## 概述

| 项 | 值 |
|------|------|
| 测试时间 | 2026-09-17（本地时区 Asia/Shanghai；末次为 09-17 代码审查与修复后的全量复测） |
| 被测系统 | QSXManager 后台管理系统（Spring Boot 3.5.16 / Java 21 / MyBatis-Plus / Spring Security + **Redis 有状态双 token**） |
| 本次改造 | 无状态 JWT access token → Redis 为唯一真相源的随机串双 token（已移除 jjwt 依赖） |
| 数据库 | MySQL 8.4（本地 Docker，`localhost:3306/QSXManager`） |
| 缓存 | Redis 7.4.9（本地 Docker，`localhost:6379`）—— 改造后为认证链路**硬依赖** |
| 应用地址 | `http://localhost:8080` |
| 测试方式 | ① 自动化集成测试 `mvn test`（MockMvc + 真实 MySQL/Redis） ② 真实 HTTP 调用（启动应用逐接口调用，记录请求与响应） |

**测试结果总览**

- 自动化集成测试：**130 个用例，全部通过（Failures: 0, Errors: 0, Skipped: 0）**，较改造前基线 88 例新增 42 例
- 真实 HTTP 端到端：**8 组场景全部符合预期**，含踢下线即时生效、刷新轮换、并发防双花、改密强制重登、禁用即时生效、保护规则、401/403 边界
- Redis 三键（at / rt / session）实测形态、TTL、字段与设计规格一致

---

## 一、改造目标与验收结论

| 目标（需求编号） | 验收方式 | 结论 |
|------|------|------|
| FR-1 登录签发双 token，响应字段名不变 | HTTP：登录返回 `token`/`refreshToken`/`userId`/`roles`/`permissions` | ✅ 通过（permissions 29 项） |
| FR-2 每请求 AT 校验（Redis 反查身份） | HTTP：有效 AT 200；删除 at 键后同一 AT 立即 401 | ✅ 通过 |
| FR-3 刷新仅凭 `{refreshToken}`，旧 rt 一经使用即失效 | HTTP + 用例：轮换后旧 AT 401、旧 RT 1019 | ✅ 通过 |
| FR-4 登出后 AT 立即失效 | 用例：登出后旧 AT 401 且三键清空 | ✅ 通过 |
| FR-5 管理员踢人即时生效 | HTTP：踢人后目标旧 AT 立即 401（改造前仍可用满 30 分钟） | ✅ 通过 |
| FR-6 禁用即时生效（含会话清理） | HTTP + 用例：禁用后旧 AT 401 且 session 键已清 | ✅ 通过 |
| FR-7 删除用户联动清理会话 | 用例：删除后三键清空、旧 AT 401 | ✅ 通过 |
| FR-8 单端登录：新登录覆盖旧会话 | 用例：二次登录后第一对 AT/RT 全部失效 | ✅ 通过 |
| FR-9 30 天绝对上限（轮换不续命） | 用例：篡改 `firstLoginTs` 后刷新 1019 且三键清空 | ✅ 通过 |
| D5 严格轮换（无服务端宽限窗口） | HTTP 并发：同一 rt 并发刷新**恰好一个 200、一个 1019** | ✅ 通过 |
| 保护规则语义不变 | HTTP + 用例：踢自己 1022、禁用自己 1022、内置超管 1020/1021 | ✅ 通过 |
| 数据库零变更 | `sql/init.sql` 与表结构未改动 | ✅ 通过 |

---

## 二、自动化集成测试（`mvn test`：130/130 通过）

| 测试类 | 用例数 | 覆盖范围 |
|------|------|------|
| `SessionLuaTest` | 13 | **会话层（直连 Redis）**：三键形态与 TTL、单端覆盖、轮换失效、重放拒绝、双保险比对、绝对上限、会话字段缺失容错、**并发双花**、**rotate 与 remove 并发后会话必不残留**、Lua 参数守卫 |
| `AuthSessionTest` | 6 | **会话生命周期（HTTP）**：登出/禁用/删除后旧 AT 立即 401 且三键清空、普通编辑不误踢、改密强制重登、改邮箱令牌仍可用、再次登录覆盖 |
| `SecurityAccessTest` | 6 | 401 边界、匿名放行、伪造令牌、**已吊销令牌立即 401**、AT 键 TTL 接线 |
| `AuthRefreshTest` | 11 | 刷新轮换、旧 AT 失效、未知/畸形令牌区分、用户不存在与禁用分支、登出失效、绝对上限、单端登录 |
| `UserKickTest` | 11 | 踢人（含旧 AT 立即 401 + 键断言）、禁用/解冻流程、删除联动、1020/1021/1022 保护、**非法 status 不得禁用账号或绕过保护** |
| `UserEdgeTest` | 15 | **边界与保护（本次新增）**：创建参数校验、分页越界、assignRoles 1004/1009 与事务回滚、**空角色清空+权限即时回收**、删除会话完整断言、**delete 超管 1025 / 自保 1022** |
| `AuthControllerTest` | 12 | 注册 / 登录 / 当前用户 / 修改密码 |
| `UserControllerTest` | 16 | 用户 CRUD、唯一邮箱、逻辑删除释放邮箱、分页筛选、非法 status 被拒 |
| `RbacTest` | 7 | 角色与权限 CRUD、分配、ADMIN 保护、整表替换、**角色编码不可变（1024）与非法 status 被拒** |
| `MenuTest` | 8 | 菜单树、防环、有子节点禁删、标识唯一 |
| `LogTest` | 6 | 操作日志分页 / 删除 / 清空 |
| `UserImportExportTest` | 9 | 模板下载 / 导入整批拒绝 / 导出 |
| `RbacCacheTest` + `RbacCacheDisabledTest` | 7 | 权限缓存回填、防穿透、变更即时失效、删除角色/权限后的失效回归、**停用角色即回收权限**、**标识不可变（1023）**、开关降级 |
| `BusinessFlowTest` | 3 | 认证→授权→业务 全链路 |

> 关键用例的**有效性**经过反向验证：临时移除「禁用分支的会话清理」后，`AuthSessionTest` 精确失败在 Redis 键存在性断言上——说明断言确实锁住了新能力，而非被每请求查库的 `isEnabled()` 兜底掩盖。

---

## 三、真实 HTTP 端到端明细

> 以下 `<at>` / `<rt>` 均为 64 位 hex 随机串前 16 位（改造后不再有 JWT 结构）。

### 3.1 登录签发与 Redis 三键

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| `POST /auth/login` | `{"email":"admin@qsx.com","password":"admin123"}` | HTTP 200 `{"code":200,"data":{"token":"5896116ff07ac8b8…","refreshToken":"5333f954cfbc42e6…","userId":527,"roles":["ADMIN"],"permissions":[29 项]}}` |
| Redis 实测 | `qsx:auth:at:{at}` | TTL=**1800s**，value=`527` |
| Redis 实测 | `qsx:auth:rt:{rt}` | TTL=**604800s**，value=`527` |
| Redis 实测 | `qsx:auth:session:{userId}` | TTL=**604800s**，fields=`accessToken,refreshToken,firstLoginTs` |
| `GET /auth/me` | `Bearer <at>` | HTTP 200 `{"code":200,"data":{"email":"admin@qsx.com"}}` |

### 3.2 踢下线即时生效（本次改造核心）

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| 注册+登录普通用户 | `u…@test.com / abc123` | code=200，userId=528，roles=[] |
| `GET /auth/me`（被踢前） | `Bearer <用户 at>` | HTTP 200 |
| `POST /api/users/528/kick` | `Bearer <超管 at>` | code=200 |
| `GET /auth/me`（被踢后，**同一个 at**） | `Bearer <用户 at>` | **HTTP 401** ← 改造前此处仍为 200，直到令牌自然过期 |
| `POST /auth/refresh` | `{"refreshToken":"<用户 rt>"}` | code=1019 |
| Redis 实测 | `EXISTS qsx:auth:session:528` | **0**（会话已被清理，非仅靠查库兜底） |

### 3.3 刷新轮换

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| `POST /auth/refresh` | `{"refreshToken":"<rt>"}`（**入参已无 userId**） | code=200，返回新 `token` 与 `refreshToken` |
| `GET /auth/me`（新 at） | `Bearer <新 at>` | HTTP 200 |
| `GET /auth/me`（轮换前旧 at） | `Bearer <旧 at>` | **HTTP 401** ← 旧 AT 随轮换立即失效 |
| `POST /auth/refresh`（重放旧 rt） | `{"refreshToken":"<旧 rt>"}` | code=1019 |

### 3.4 并发刷新（防双花）

| 操作 | 返回信息 |
|------|------|
| 同一 refresh token 并发两次 `POST /auth/refresh` | code=**1019** 与 code=**200** ← 恰好一个成功、一个被拒（Lua 原子性 + 严格轮换） |

### 3.5 改密强制重新登录

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| `POST /auth/change-password` | `Bearer <at>`；`{"oldPassword":"abc123","newPassword":"abc124"}` | code=200 |
| `GET /auth/me`（改密前的 at） | `Bearer <旧 at>` | **HTTP 401** |
| `POST /auth/refresh`（改密前的 rt） | `{"refreshToken":"<旧 rt>"}` | code=1019 |
| `POST /auth/login`（新密码） | `{"email":"u…@test.com","password":"abc124"}` | code=200 |

### 3.6 禁用即时生效

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| `PUT /api/users/528` | `Bearer <超管 at>`；`{"email":"u…@test.com","status":1}` | code=200 |
| `GET /auth/me`（被禁用的 at） | `Bearer <用户 at>` | **HTTP 401** |
| Redis 实测 | `EXISTS qsx:auth:session:528` | **0**（会话已清） |

### 3.7 保护规则与边界

| 操作 | 携带信息 | 返回信息 |
|------|------|------|
| `POST /api/users/527/kick`（踢自己） | `Bearer <超管 at>` | code=1022 |
| `PUT /api/users/527` `status=1`（禁用自己） | `Bearer <超管 at>` | code=1022 |
| `POST /auth/refresh` `{"refreshToken":"forged-not-hex"}` | 无认证 | code=**1019**（形态非法，形态校验在服务层、不打 Redis；与其它 refresh 失败同一契约） |
| `POST /auth/refresh` `{"refreshToken":"<64 位 hex 未签发>"}` | 无认证 | code=**1019** |
| `GET /api/users` | 无认证 | HTTP 401 |
| `GET /api/users` | `Bearer <无 user:page 权限的普通用户>` | HTTP 403 |
| `GET /api/menus/current` | `Bearer <无角色普通用户>` | HTTP 200 `data=[]` |

### 3.8 旧 schema 残留

| 项 | 值 |
|------|------|
| `qsx:auth:refresh:*`（改造前 schema） | 0 个键——改造后无任何代码读取，若有残留也只会等 TTL 自然过期 |
| `qsx:auth:*` 键总数 | 5（当前存活会话的三键 + 轮换途中令牌） |

---

## 四、实施中发现并修正的问题

| # | 问题 | 影响 | 处理 |
|------|------|------|------|
| 1 | `HGETALL` + 字段访问在 Redis Lua（RESP2）中无效——返回的是数组而非 map，`t.accessToken` 恒为 nil | **「即时踢下线」会静默失效**：`remove` 永远删不掉 AT 键，而测试因每请求查库的 `isEnabled()` 兜底仍会通过 | 改为逐字段 `HGET`，并在脚本注释中写明原因 |
| 2 | `StringRedisTemplate` 的脚本参数会被 `StringRedisSerializer` 硬 `checkcast String` | 传 `Long`/`Integer` 会在触达 Redis 前抛 `ClassCastException` → **登录全线 500** | 全部 ARGV 以字符串传入（已用 `javap` 核实字节码） |
| 3 | 刷新采用「先轮换、失败再补偿删除」时序 | 数据库抖动会烧掉用户有效会话（全体被迫重登）；补偿删除还会误删并发建立的新会话 | 改为「只读反查 → 查库 → 原子轮换」，**不做任何补偿** |
| 4 | 测试基类 `DELETE FROM sys_user` 全表物理删除 | 每次 `mvn test` 都会删掉预置超管 `admin@qsx.com`，需反复手工恢复（历史已多次发生） | 改为仅删测试用户（`email LIKE '%@test.com%'`），种子数据存活；并修正 `page_all` 依赖「库里只有测试用户」的绝对计数断言为相对增量断言 |
| 5 | 令牌形态校验引入后，畸形令牌语义变化 | 原用例用 `forged-token-value` 断言 1019，改为 `@Pattern` 后会返回 400 | 09-17 评审判定 400 会破坏「refresh 失败一律 1019」的前端契约，已去掉 `@Pattern`：形态校验下沉服务层，统一返回 1019（仍早于任何 Redis 访问） |

### 09-17 代码审查与修复（用例 109 → 115）

以双 token 为核心的九角度审查（含被移除行为审计、语言陷阱、封装正确性等）+ 独立运行时复现，修复如下（详见 `docs/session-notes/qsxmanager/会话总结-qsxmanager-review-fix-20260917.md`）：

| 级别 | 问题 | 处理 |
|------|------|------|
| P0 | `status` 无取值约束 + 「禁用」谓词两套（`!=0` vs `==1`）→ 传 `status=2` 可**禁用超管/禁用自己**（绕过 1020/1022）且不清理会话，解冻后旧令牌复活 | DTO 加 `@Min/@Max`；服务层抽出 `isDisabling`（非 0 即禁用）统一保护与清理；实测修复前后对照全部符合预期 |
| P1 | refresh 第 1 步 Redis 异常返回 500、第 3 步返回 1019（同一故障两种契约） | 两处统一映射 1019 |
| P1 | `RefreshRequest.@Pattern` 让形态非法令牌返回 400，破坏「失败一律 1019」契约 | 去掉 `@Pattern`，校验下沉服务层，统一 1019 |
| P1 | 改密后会话清理失败被吞、仍返回 200（改密无兜底网，止损落空） | 日志提级 ERROR |
| P1 | 会话配置无防呆（`max-lifetime: 30` 会被绑成 30 毫秒） | 启动期校验（≥1 分钟且 at ≤ rt ≤ max-lifetime） |
| P2 | **停用角色不回收权限**（权限查询不过滤 `sys_role.status`） | 查询加 `r.status = 0`，停用即真正回收 |
| P2 | **标识可被改名锁死接口**（含角色编码改名可绕过超管保护） | 权限/菜单标识与角色编码创建后不可修改（1023 / 1024） |
| P2 | `delete()` 会话清理在事务内，回滚会登出未被删除的用户 | 改为提交后执行（AFTER_COMMIT） |
| P2 | Redis 故障时 100% 请求 401，逐条写操作日志形成写库风暴 | 基建异常打标记，401 处理器跳过落库 |

### 09-17 delete 保护补齐（用例 115 → 130）

代码审查发现 `delete()` 相比 `update`（禁用超管 1020 / 禁自己 1022）、`kick`（踢超管 1021 / 踢自己 1022）**缺少同类保护**：管理员可删除内置超管或删除自己，破坏保护矩阵对称性。修复并配套（详见 `docs/design/用户与会话管理机制说明.md`）：

| 项 | 内容 |
|------|------|
| 新增业务码 | `1025 内置超管用户不可删除`（`ResultCode`） |
| `delete()` 保护 | 先判删自己→1022、再判删内置超管→1025（用 `selectRoleCodes(...ADMIN)`，口径与禁用/踢人一致） |
| 回归测试 | 新增 `UserEdgeTest`（15 例）锁定，含 delete 超管 1025 / 自保 1022 |
| 真实 HTTP 复核 | 删除内置超管→1025、删自己→1022、禁超管→1020、踢超管→1021、401/403 边界全部命中 |

### 观察项（保留现状）

- `GET /auth/login`（方法不允许）触发 `HttpRequestMethodNotSupportedException`，落入兜底处理器返回 code=500 **且不记录操作日志**——同类还有畸形 JSON、超长上传、404 等；属既有行为，未处理。
- 权限缓存回填竞态：09-17 评审确认**维持现状**，记录为有界陈旧（毫秒级窗口 + TTL 兜底）。
- `/auth/logout` 未在白名单内，Redis 故障期间用户无法登出（fail-closed 的必然结果，有意保留）。
- 会话脚本仅支持单节点 Redis（无 hash tag，上集群会 CROSSSLOT）——设计约束。

---

## 五、结论

- 认证体系已从「无状态 JWT + Redis 哈希型 refresh token」切换为「Redis 为唯一真相源的随机串双 token」，**登出 / 踢人 / 禁用 / 删除 / 改密后旧 access token 立即失效**，根治了改造前最长 30 分钟的踢人残留窗口。
- 顺带消除一个既有竞态：改造前刷新是「GET → 改 → SET」的非原子读改写，一次与踢人并发的刷新会把刚被踢掉的会话重新写回并续上新的 7 天 TTL；Lua 原子化后该缺陷消失，并有并发回归用例锁住。
- 自动化用例 **130/130** 通过（含 09-17 审查修复后的 6 个回归用例与 delete 保护的 15 例 `UserEdgeTest`）；真实 HTTP 端到端与自动化结果互相印证；数据库结构零变更；RBAC、操作日志、Excel 等模块不受影响（全量回归通过）。
- **运维注意**：Redis 已成为认证链路硬依赖，故障即全员 401 且无法登录；已补 `timeout: 2s` / `connect-timeout: 1s`，建议补充 Redis 监控告警与可用性方案。上线瞬间旧 JWT 全部失效，全员需重新登录。

---

*本报告取代 2026-09-12 版（66 用例 / JWT 时代）；历史报告见 `docs/session-notes/` 下各期测试报告。*
