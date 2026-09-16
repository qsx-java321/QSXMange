# QSXManager 全项目主线 - 总总结

> 类型：全项目总总结（稳定文件名，随里程碑更新）｜ 最近更新：2026-09-17
> **读者导航**：想快速了解项目全貌、当前状态、跨阶段决策与共性坑 → 读本文件；想查某阶段的实施细节 / 测试报告 → 读下方「阶段导航」指向的对应增量文件（增量保留作历史快照，不删除）。

## 〇、项目现状快照（截至 2026-09-17）

- **定位**：单体单模块、前后端分离中小型后台管理系统（Spring Boot 3.5.16 / Java 21 / MyBatis-Plus / MySQL 8.4 / Redis / **无 JWT，令牌为随机串**），根包 `com.qsx`，分支 `master`
- **已完成七大模块**：认证中心（双令牌会话）→ RBAC 权限（权限码 + 菜单树复用一表）→ 操作日志（AOP 审计）→ Excel 批量导入导出 → 权限缓存（Redis）→ 会话管理（登出 / 强制登出 / 禁用解冻保护）→ **双 token 有状态会话（Redis 唯一真相源，吊销即时生效）**
- **工程记忆红线**（项目 memory / wrap 技能固化，本文件不重复抄写）：权限表只读、ADMIN 禁删、整表替换授权、逻辑删除释放邮箱、AFTER_COMMIT 失效缓存、fail-open 权限缓存 / fail-closed 会话、测试双轨制
- **最新提交**：`814992d`（权限缓存失效断链修复）、`40bcfc0`~`b03d1ec`（双 token 会话改造 M1~M5）、本次（双 token 机制代码审查与 P0/P1 修复）；自动化用例 **112/112** 全绿

## 一、发展阶段脉络（时间线主线）

### 阶段 1 · 基础版（09-10）：工程 + 认证中心 + 用户管理
从 0 搭建（统一 Result/异常、JWT 认证、用户 CRUD），确立「逻辑删除释放邮箱」「唯一邮箱即账号」等基石。35 用例全过。
→ 细节见 `会话总结-qsxmanager-20260910.md`

### 阶段 2 · RBAC 权限管理（09-10）
新增 4 表成标准 RBAC 5 表；确立**权限码模型** + `@PreAuthorize` 方法级鉴权 + 每请求查库即时生效；`ADMIN` 角色绑定全量权限（非硬编码）；内置超管禁删。40 用例全过。
→ 细节见 `会话总结-qsxmanager-rbac-20260910.md`

### 阶段 3 · 菜单管理（09-11）：动态路由菜单树
**复用 `sys_permission` 表承载菜单**（type=MENU/PERMISSION，parent_id 成树），`/api/menus/current` 按权限过滤 + 补祖先链供前端动态路由；防环、有子禁删。48 用例全过。
→ 细节见 `会话总结-qsxmanager-menu-20260911.md`

### 阶段 4 · 操作日志（09-11）：AOP 访问审计
包级切面（非注解）全量审计 + `@Async` 异步落库 + 401/403/400 三处补记；日志表不可变。15 个真实穿透场景验证。
→ 细节见 `会话总结-qsxmanager-log-20260911.md` + `测试报告-qsxmanager-log-20260911.md`

### 阶段 5 · Excel 批量导入导出（09-12）
EasyExcel 流式（文件不落盘）、整批校验整体拒绝、模板含角色列、默认密码配置化；同期 `docs/` 目录整理（`.trae/` gitignore、session-notes 按类别分文件夹）。63 用例全过。
→ 细节见 `会话总结-qsxmanager-excel-20260912.md` + `测试报告-qsxmanager-excel-20260912.md`

### 阶段 6 · RBAC 权限缓存（09-12，Redis）
三处读取点收口 `PermissionCacheService.load`（Redis 优先 + 回源回填 + 空权限防穿透 + enabled 开关）；`AFTER_COMMIT` 事件精确失效；同步补事务注解修复整表替换原子性问题。66 用例 + 真实 HTTP 38 调用全过。
→ 细节见 `会话总结-qsxmanager-redis-20260912.md` + `docs/test/test-report.md`（该阶段实施计划已随完成归档删除）

### 阶段 7 · 会话管理（09-13，refresh token）：登出 / 强踢 / 禁用解冻
**双令牌会话**：access 30min + refresh（Redis 哈希、单端、滑动 7d + 30d 上限、轮换语义、fail-closed）。新增 `/auth/refresh`、`/auth/logout`、`/api/users/{id}/kick`（user:kick）。**禁用=status 赋值**（自带踢下线），补禁用自己/超管保护。85 用例全过 + 真实 HTTP 全链路。
→ 细节见 `会话总结-qsxmanager-refresh-20260913.md`

### 阶段 8 · 权限缓存失效断链修复（09-16）
事件载荷由「角色/权限 id」改为「发布方预先反查的 userId 集合」：原实现删角色/权限时先清关联表，AFTER_COMMIT 再回查必然得到空集 → 已回收的权限仍生效至 TTL 到期。同步补权限标识变更的失效与事务，反查下沉 mapper 消除 N+1。88 用例全绿（新增 3 例，已验证修复前精确失败）。
→ 细节见本次提交 `814992d`

### 阶段 9 · 双 token 有状态会话改造（09-16）：吊销即时生效
认证从「无状态 JWT + Redis 哈希型 refresh」切换为 **Redis 为唯一真相源的随机串双 token**（at/rt/session 三键，Lua 原子脚本）：登出/踢人/禁用/删除/改密后旧 access token **立即失效**。废除 jjwt 全链路；refresh 入参收敛为 `{refreshToken}`。顺带消除「踢下线被在途刷新撤销」的既有竞态。109 用例全绿 + 真实 HTTP 八组场景。
→ 细节见 `会话总结-qsxmanager-session-refactor-20260916.md` + `docs/test/test-report.md`

### 阶段 10 · 双 token 机制代码审查与修复（09-17）
以双 token 为核心的多角度代码审查（逐行/跨文件/被移除行为/语言陷阱/封装/复用/简化/效率/规范/抽象高度），并逐条运行时复现。**P0**：`status` 无取值约束 + 「禁用」谓词两套（`!=0` vs `==1`）→ 传 `status=2` 可绕过禁用保护（禁用超管、禁用自己）且不清理会话、解冻后旧令牌复活，最坏可锁死系统；**P1** 4 项：refresh 两处异常码不一致、`@Pattern` 破坏 1019 契约、改密清理失败静默、会话配置无防呆。全部已修并补回归用例（109→112）。核心机制本身未发现缺陷。
→ 细节见 `会话总结-qsxmanager-review-fix-20260917.md`

## 二、跨阶段关键决策与演进（横切视角）

- **数据模型演进**：`sys_user` 单表 → RBAC 5 表（关系表物理删除）→ `sys_permission` 一表两用（菜单+按钮权限）→ 日志表不可变
- **认证演进**：单 JWT 24h 无状态 → 权限每请求查库 → 权限 Redis 缓存（fail-open 降级）→ 双令牌会话（会话层 fail-closed）→ **有状态随机串双 token（Redis 唯一真相源，AT 可即时吊销）**
- **鉴权模式（贯穿）**：权限码 + `@PreAuthorize`，ADMIN 绑定全量权限——新增权限须同步 init.sql 预置（INSERT IGNORE + 自动绑 ADMIN）
- **「变更即时生效」原则**：权限靠每请求加载 + AFTER_COMMIT 失效；禁用靠每请求查库 status；因此权限变更不需要踢人
- **测试体系演进**：35 → 40 → 48 → 63 → 66 → 85 → 88 → 109 → **112** 用例；MockMvc + 真实 MySQL/Redis 集成测试 + 真实 HTTP 双轨制；`adminToken()` 走真实 assignRoles 链路；会话层 Lua 有专项直连测试（含并发双花）

## 三、跨阶段共性教训（高频坑沉淀）

- **测试清理语义**（三代演进）：`userMapper.delete(null)` 全表**逻辑**删除（污染预置 admin）→ JdbcTemplate 全表**物理**删除（仍会删掉预置超管，需反复手工恢复）→ **仅清测试用户**（`email LIKE '%@test.com%'`）。教训：测试清理的作用域必须显式限定，任何"全表"清理都会迟早误伤种子数据
- **PowerShell 三大坑**：内嵌 JSON 用反引号转义引号（勿用 `\"`）；多级属性插值先赋标量变量；多行 commit 用多个 `-m`（heredoc 不可用）、`-Dtest=A,B` 加引号
- **MockMvc 断言陷阱**：业务失败是 HTTP 200 + 业务码 ≠ 200，`andExpect(isOk())` 拦不住，必须断言业务码
- **预置超管易被测试污染**：已于 09-16 从清理作用域上根治（仅清 `*@test.com`）；仍建议真实黑盒验证前扫一眼 `sys_user` 是否有该账号
- **Lua/Redis 坑**（09-16 新增）：`HGETALL` 在 RESP2 下是数组（字段访问恒为 nil）；脚本参数必须全为字符串（`StringRedisSerializer` 硬 cast）；Lua 报错不回滚已执行的写，故校验前置 + 写入顺序「先 session 后令牌键」；`EXPIRE k 0` 会直接删键而 `SET ... EX 0` 报错
- **docker exec 传 SQL**：避免嵌套引号地狱，用管道或 `docker exec mysql mysql` 直连方式

## 四、遗留事项（截至 09-17）

- [x] access token 黑名单（禁用/踢人即时中断）——09-16 由「双 token 有状态会话」从根上解决，无需黑名单
- [ ] 邮箱验证码注册
- [ ] 前端管理界面（对接菜单树动态路由）
- [ ] **权限缓存回填竞态**：失效事件后仍可能被在途请求用旧值回填，最长残留 30 分钟（09-17 审查发现）
- [ ] **停用角色不回收权限**：权限查询不过滤 `sys_role.status`（09-17 审查发现）
- [ ] **权限 code 可被改名**：会锁死对应接口且无法在界面改回（09-17 审查发现）
- [ ] 会话清理时机（事务内 vs AFTER_COMMIT）、登出按 userId 吊销的窄竞态、Redis 故障 401 写库放大、会话脚本仅支持单节点（09-17 审查发现）
- [ ] Redis 可用性加固（认证链路硬依赖：故障即全员 401，需监控告警，必要时 Sentinel）
- [ ] 明文令牌风险面收口（AOF 文件权限、禁 MONITOR、slowlog 策略；未来可将 key 换 SHA-256，收敛在 `AuthSessionServiceImpl` 单类内）
- [ ] 操作日志放大与兜底缺口（Redis 故障时 401 洪峰写库；未记录方法不允许/畸形 JSON 等兜底异常）