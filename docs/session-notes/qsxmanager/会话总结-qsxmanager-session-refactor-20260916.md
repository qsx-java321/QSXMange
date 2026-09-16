# QSXManager 双 Token 有状态会话改造 - 会话总结

> 总结时间：2026-09-16 ｜ 来源：当前会话（认证体系从无状态 JWT 切换为 Redis 有状态双 token）

## 〇、本次状态变化

- **新增**：14 个文件
  - 主代码 10：`AuthRedisKeys`、`AuthSession`、`AuthSessionService`、`AuthSessionServiceImpl`、`AuthSessionProperties`、`AuthSessionLuaConfig`、`TokenProvider`、`TokenAuthenticationFilter`、`lua/auth_session_issue.lua`、`lua/auth_session_rotate.lua`、`lua/auth_session_remove.lua`（其中 `TokenProvider`/`TokenAuthenticationFilter` 为对 `JwtTokenProvider`/`JwtAuthenticationFilter` 的重写）
  - 测试 3：`SessionLuaTest`（13 例）、`AuthSessionTest`（6 例）、`docs/test/test-report.md`（重写）
- **修改**：`AuthServiceImpl`、`UserServiceImpl`、`SecurityUserDetailsService`、`SecurityConfig`、`SecurityConstants`、`RefreshRequest`、`AuthService`、`RefreshVO`、`PermissionServiceImpl`（注释）、`application.yml`、`pom.xml`、`README.md`、`docs/design/springboot项目设计.md`、`BaseIntegrationTest`、`UserControllerTest`
- **删除**：`RefreshTokenServiceImpl`、`RefreshTokenService`、`RefreshSession`、`JwtProperties`、`JwtTokenProvider`、`JwtAuthenticationFilter`、jjwt 三件套依赖、`SecurityConstants.WHITELIST`（死代码）
- **完成度变化**：认证从「无状态 JWT（30 分钟）+ Redis 哈希型 refresh」升级为「Redis 为唯一真相源的随机串双 token」，**登出/踢人/禁用/删除/改密后旧 access token 立即失效**
- **遗留事项变化**：
  - 解决：README 后续规划中的「access token 黑名单（禁用/踢人即时中断）」——本次以有状态会话直接达成，无需黑名单
  - 新增遗留：Redis 成为认证链路硬依赖（故障即全员 401），需运维侧监控告警；明文令牌进入 AOF/SLOWLOG/MONITOR 的风险面待运维收口

## 一、基础上下文

- **项目 / 主题**：QSXManager 后台管理系统 —— 双 token 有状态会话改造（M1~M5）
- **时间范围**：2026-09-16 单次会话（承接当日已完成的两件事：权限缓存失效断链修复 `814992d`、双 token 方案评审定稿）
- **涉及范围**：认证与会话（security/session、filter、token）、用户生命周期（UserService）、配置与依赖、测试基建、README 与设计文档；分支 master
- **前置文档**：方案落于 `~/.claude/plans/`（实施计划全文），仓库内原 `docs/plans/auth-session-refactor-*.md` 已按惯例归档删除（其 Lua 写法存在硬缺陷，见下）

## 二、已完成任务（按里程碑，每步一个提交）

| 里程碑 | 提交 | 内容 |
| :--- | :--- | :--- |
| M1 会话领域层 | `40bcfc0` | 三键模型 + 三条 Lua 脚本 + `AuthSessionService` + `TokenProvider` + 配置；`SessionLuaTest` 13 例（含并发双花、rotate 与 remove 并发后会话必不残留） |
| M2 认证链路切换 | `c7d8d0f` | 新过滤器（每请求 Redis 反查 + 实时查库）、`loadUserById`、login/refresh/logout/changePassword 改造、删除 JWT 全链路与 jjwt 依赖、补 Redis 超时配置 |
| M3 用户生命周期 | `197245b` | 禁用即清会话；`AuthSessionTest` 6 例；`UserKickTest` 补 Redis 键断言 |
| M4 回归与端到端 | `41475d6` | 109/109 全绿、真实 HTTP 八组场景、测试报告落盘；测试基建不再误删预置超管 |
| M5 文档收尾 | 本次提交 | README / 设计文档 / 本会话总结 |

- **三键模型**：`qsx:auth:at:{at}` → userId（30min，删除即吊销）、`qsx:auth:rt:{rt}` → userId（7d）、`qsx:auth:session:{userId}` → Hash{accessToken, refreshToken, firstLoginTs}（7d 滑动）
- **接口契约变化**：`POST /auth/refresh` 入参由 `{userId, refreshToken}` 收敛为 `{refreshToken}`；新增令牌形态校验（形态非法 400，形态合法但未知/失效 1019）
- **业务行为变化**：改密后强制重新登录；禁用→解冻后旧令牌不再复活；单端登录语义不变
- **验证结果**：自动化 **109/109 全绿**（改造前基线 88）；真实 HTTP 端到端 8 组场景通过（踢下线即时生效、轮换、并发防双花恰好一成功、改密重登、禁用即时生效、保护规则、401/403 边界、三键 TTL 实测 1800s/604800s）

## 三、错误与教训

- **方案文档里的 Lua 是错的（最危险）**：`local old = HGETALL qsx:auth:session:{id}` 后访问 `old.accessToken` —— RESP2 下 `HGETALL` 返回的是 **Lua 数组（1 基下标）而非 map**，字段访问恒为 `nil`。照抄会导致 `remove()` **永远删不掉 AT 键**，「即时踢下线」静默失效且不报错；更隐蔽的是：现有测试只断言 401，而 401 会被每请求查库的 `isEnabled()` 兜底掩盖，**测试照样全绿**。教训：Lua 里读 Hash 字段用 `HGET`；断言要打到机制层（Redis 键存在性），不能只看 HTTP 状态码。
- **`StringRedisTemplate` 的脚本参数必须是 String**：底层 `StringRedisSerializer.serialize(Object)` 是硬 `checkcast String`（`javap` 核实过字节码），传 `Long` 会在触达 Redis 之前抛 `ClassCastException` → **登录全线 500**。全部 ARGV 用 `String.valueOf()` 传入，脚本内 `tonumber()`。
- **刷新时序：不要补偿式删除**：最初设计为「先轮换 → 再查库 → 不通过则补偿 `remove`」。问题是：① 数据库抖动会让旧 RT 已被销毁、新令牌又没下发，等于白白烧掉用户会话（全体被迫重登）；② 补偿删除会误删并发建立的新会话，且查库抛异常时补偿根本不执行，反而留下孤儿会话。改为「只读反查 → 查库 → 原子轮换」，并把「为什么不需要补偿」写进类注释——否则后人会"好心"加回来。
- **测试基类全表删除误伤预置数据**：`DELETE FROM sys_user` 会把预置超管 `admin@qsx.com` 一并删掉，导致「跑一次 `mvn test` 就要手工恢复一次 admin」（历史上反复发生，09-13 的会话总结已记录过一次）。本次改为只清测试用户（`email LIKE '%@test.com%'`，带尾部通配以覆盖逻辑删除后带 `#deleted_` 后缀的邮箱）。**连带修正**：`UserControllerTest.page_all` 原先断言绝对总数 4，隐含依赖「库里只有测试用户」，改为取基线后断言增量。
- **断言要能证伪**：M3 的「禁用即清会话」用例在被判定有效前，先临时移除实现跑了反向验证——确认精确失败在 Redis 键断言上，而不是恰好也被 `isEnabled()` 兜底通过。
- **其它 Lua/Redis 坑**（已写进脚本注释）：Lua 报错**不回滚**已执行的写，故参数校验必须前置、写入顺序固定为「先清旧 → 写 session → 写令牌键」（崩溃只留死令牌，不留无法吊销的裸令牌）；`SET k v EX 0` 报错而 `EXPIRE k 0` 会直接删键，故 TTL 下限强制为 1；脚本落空返回 `nil` 会让 Java 侧 NPE，每条路径都要显式 `return`。

## 四、关键技术抉择

- **AT 形态：随机串而非 JWT**（用户拍板，废除 jjwt）：身份完全由 Redis 映射决定，改邮箱不再让令牌失配；代价是 Redis 成为认证链路硬依赖。
- **明文令牌作 key**（用户拍板，沿用原方案 D3）：实现直白、可在 redis-cli 直接查会话；风险面（拖库 + AOF/SLOWLOG/MONITOR）已登记，未来收紧（key 换 SHA-256）收敛在 `AuthSessionServiceImpl` 单类内。
- **严格轮换 + 前端单飞**（用户拍板，不做服务端宽限窗口）：旧 RT 一经使用立即失效，并发刷新恰好一个成功、其余 1019；前端契约写入 README。
- **轮换时立即删除旧 AT**：单端语义下保留旧 AT 就是保留那个 30 分钟的踢人漏洞；代价是在途请求会 401，前端应重试而非直接登出。
- **刷新先查库、后轮换**：牺牲一次 O(1) 的 Redis GET，换取「数据库故障不烧会话」。
- **改密即清理会话**（本次会话新增决策，需求文档 FR-1~FR-9 未覆盖）：密码可能已泄露，继续沿用旧令牌会让改密失去止损意义。
- **会话清理沿用直调、不引入 AFTER_COMMIT 事件**：与权限缓存失效的时机（AFTER_COMMIT）不一致，但事务回滚时"用户需重新登录"的影响可接受，换取不新增事件机制。
- **fail 策略分级固化**：会话层 fail-closed（宁可拒绝认证），权限缓存 fail-open（降级查库）。
- **放弃/未做**：access token 黑名单（本次改造已从根上解决，无需黑名单）；旧 `qsx:auth:refresh:*` 键的一次性清理脚本（无代码读取，等 TTL 自然过期，避免引入"上线后还要记得删"的临时代码）；`/auth/logout` 在 Redis 故障期间无法登出（fail-closed 的必然结果，已登记）。
