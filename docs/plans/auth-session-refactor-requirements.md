# QSXManager 双 Token 会话机制改造 - 需求文档

> 状态：已评审（2026-09-16 四项决策确认完毕），等待实施
> 关联文档：`docs/plans/auth-session-refactor-plan.md`（实施计划方案）

## 1. 背景与动机

当前认证体系为「无状态 JWT access token + Redis 哈希型 refresh token」：

| 项 | 现状 |
| :--- | :--- |
| AT | JWT（载荷 subject=email、claim=userId），30min，签发后不可吊销 |
| RT | 32 字节随机 hex，Redis 只存 SHA-256 哈希：`qsx:auth:refresh:{userId}`，7d 滑动 + 30d 绝对上限，轮换 |
| 认证 | 每请求验 JWT 签名 + 实时查库加载用户，权限走 `qsx:auth:perm:` 缓存 |
| refresh | `POST /auth/refresh`，入参 `{userId, refreshToken}` |

存在痛点：

1. **AT 不可主动吊销**：管理员踢人、用户登出后，旧 AT 仍可用满 30 分钟（踢人残留漏洞）。
2. **refresh 依赖客户端传 userId**：由 RT 自身即可映射用户，不应由客户端自报身份。
3. **会话结构单一**：现有 key 只存 RT 哈希，无法按用户索引完整会话（AT+RT）全貌，批量清理、多端扩展困难。
4. **JWT 载荷与用户数据耦合**：email 写入 AT 载荷，改邮箱后旧 token 载荷过期，存在不一致风险。

## 2. 改造目标

将认证体系改造为「双 Token 有状态会话」：access token 与 refresh token 均以 Redis 为唯一真相源，AT 可主动吊销（踢人/登出/禁用/删除立即生效），refresh 校验无需 userId，单端登录语义统一收敛到用户维度的会话索引结构。

## 3. Redis 模型规格

### 3.1 原始需求

- `auth:at:{accessToken}` → userId，TTL 30min
- `auth:rt:{refreshToken}` → userId，TTL 7d
- `auth:session:{userId}` → Hash {accessToken, refreshToken}，TTL 7d

### 3.2 评审决策（2026-09-16 确认）

| # | 决策点 | 结论 |
| :--- | :--- | :--- |
| D1 | AT 形态 | **随机串**（32 字节 SecureRandom hex，与 RT 同构），放弃 JWT，删除 jjwt 依赖 |
| D2 | key 前缀 | **qsx:auth:**（与现有 `qsx:auth:perm:` 统一） |
| D3 | RT 存储方式 | **明文作 key**（`qsx:auth:rt:{refreshToken}` → userId） |
| D4 | 会话绝对上限 | **保留 30 天**（session Hash 增加 firstLoginTs 字段，轮换不重置） |

### 3.3 最终 key 规格

| Key | Value | TTL | 职责 |
| :--- | :--- | :--- | :--- |
| `qsx:auth:at:{accessToken}` | userId(string) | 30min | AT 有效性与身份来源，删除即吊销 |
| `qsx:auth:rt:{refreshToken}` | userId(string) | 7d | RT→userId 映射；轮换时删旧立新 |
| `qsx:auth:session:{userId}` | Hash{accessToken, refreshToken, firstLoginTs} | 7d（滑动） | 用户维度会话索引：单端覆盖、按用户清理、绝对上限判定 |

凭证规格：AT/RT 均为 32 字节安全随机数 hex 编码（64 字符），无签名、无载荷、不可解析出任何用户信息。

## 4. 功能需求

| 编号 | 需求 | 说明 |
| :--- | :--- | :--- |
| FR-1 | 登录签发双 Token | 认证成功后签发 AT、RT，按 3.3 模型写入 Redis，返回字段名不变（token/refreshToken/userId） |
| FR-2 | 每请求 AT 校验 | 过滤器从 `qsx:auth:at:{at}` 反查 userId，不存在视为未认证；仍保留每请求实时查库加载用户（禁用/删除即时生效兜底） |
| FR-3 | 刷新轮换 | `POST /auth/refresh` 入参改为 `{refreshToken}`；rt 校验 → 双保险比对 session → 绝对上限判定 → 轮换（旧 rt 立即失效，重放返回 1019） |
| FR-4 | 用户登出 | 按自身 userId 清理 at/rt/session 三键，AT 立即失效 |
| FR-5 | 管理员强制踢人 | `POST /api/users/{id}/kick` 清理目标用户三键；保护规则不变（禁止踢自己 1022、禁止踢超管 1021） |
| FR-6 | 禁用即时生效 | 更新 status=1 时同步清理会话（新增）；每请求查库 isEnabled 为兜底防线 |
| FR-7 | 删除用户联动 | 逻辑删除后清理会话三键（Redis 异常仅告警不阻断删除） |
| FR-8 | 单端登录 | 新登录覆盖旧会话，旧端 AT/RT 全部立即失效（保持现有语义） |
| FR-9 | 30 天绝对上限 | 自首次登录（firstLoginTs）起超 30 天强制重新登录，轮换不续命 |

## 5. 非功能需求

| 编号 | 需求 | 说明 |
| :--- | :--- | :--- |
| NFR-1 | 原子性 | 登录/轮换/清理全部通过 Lua 脚本原子执行，消除并发竞态 |
| NFR-2 | fail 策略分级 | 会话层 fail-closed（Redis 异常拒绝认证/拒绝发放）；应用数据层（权限缓存）保持 fail-open（降级查库）。AT 为随机串，Redis 故障即全员 401，此为已确认取舍 |
| NFR-3 | 性能 | 每请求新增 1 次 Redis GET（O(1)），配合现有查库 + 权限缓存 GET，可接受 |
| NFR-4 | 随机性 | AT/RT 使用 SecureRandom 32 字节，猜测/碰撞概率 2^-256 |
| NFR-5 | 兼容性 | LoginVO/RefreshVO 字段名不变，前端 AT 形态变化无感；唯一前端联调点为 refresh 入参 |

## 6. 设计约束（不变量）

1. 权限缓存整条链路不动：`qsx:auth:perm:{userId}`、`@TransactionalEventListener` 失效事件、fail-open 降级均保持现状。
2. `ResultCode` 复用 1019（REFRESH_TOKEN_INVALID），不新增业务码。
3. 数据库无任何结构变更，`sql/init.sql` 不动。
4. RBAC 接口、操作日志切面、Excel 导入导出模块均不受影响。
5. 踢人/禁用保护规则（1020/1021/1022）语义不变。

## 7. 验收标准

1. 登录返回 AT/RT，Redis 中三键符合 3.3 规格且 TTL 正确。
2. 携带有效 AT 可访问受保护接口；AT 被删除（踢人/登出/禁用/删除用户/二次登录覆盖）后，同一 AT 立即 401。
3. refresh 仅凭 `{refreshToken}` 可完成轮换；旧 rt 重放返回 code 1019；rt 与 session 不匹配返回 1019。
4. 会话超过 30 天绝对上限后 refresh 返回 1019 且会话被整体清理。
5. 单端登录：同一用户二次登录后，旧 AT/RT 全部失效。
6. 踢人、禁用、删除均不影响保护规则（ADMIN 不可踢/不可禁用，不可操作自己）。
7. 全量 MockMvc 集成测试通过（改造前 85 用例基线），新增用例覆盖上述行为。
8. 真实 HTTP 端到端验证，测试报告落盘 `docs/test/`（沿用既定格式）。