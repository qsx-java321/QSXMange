# QSXManager 会话管理（refresh token）- 会话总结

> 总结时间：2026-09-13 ｜ 来源：当前会话（认证与会话管理改造）

## 〇、本次状态变化
- **新增**：8 个文件 —— `RoleConstants`、`RefreshSession`、`RefreshTokenService`、`RefreshTokenServiceImpl`、`RefreshRequest`、`RefreshVO`、`AuthRefreshTest`、`UserKickTest`
- **修改**：18 个文件 —— README、`docs/design/springboot项目设计.md`、`sql/init.sql`、核心认证/用户链路（AuthService 及其实现、UserService 及其实现、JwtTokenProvider、SecurityConfig、SecurityConstants、PermissionConstants、ResultCode、JwtProperties、LoginVO、AuthController、UserController、application.yml）、`BaseIntegrationTest`
- **完成度变化**：认证从「单 JWT 24h 无状态、无法登出」升级为「access 30min + refresh token 会话（Redis）」，支持登出、管理员强制登出（踢下线）、禁用/解冻保护闭环
- **遗留事项变化**：
  - 解决：会话管理（登录/登出/强踢/禁用联动）整体完成并提交推送（cd73263）
  - 新增遗留：access token 黑名单（禁用/踢人即时中断，当前依赖实时查库 status + 30min 令牌窗口）——列入 README 后续规划

## 一、基础上下文
- **项目 / 主题**：QSXManager 后台管理系统 —— 引入 refresh token 会话机制 + 用户禁用/强制登出
- **时间范围**：2026-09-13 单次会话
- **涉及范围（文件 / 模块 / 分支）**：认证模块（Auth）、用户模块（User）、安全配置（security）、`sql/init.sql`、测试基类与新增测试、README 与设计文档；分支 master

## 二、已完成任务
- **access token 24h → 30min**；新增 refresh token（32 字节 SecureRandom hex，Redis 仅存 SHA-256 哈希，key `qsx:auth:refresh:{userId}`）：单端登录、滑动续期 7d、30d 绝对上限、**轮换语义**（旧 refresh 一经使用立即失效）、**fail-closed**（Redis 异常拒绝续期）——`RefreshTokenServiceImpl`、`JwtProperties`（refresh-expiration / refresh-max-lifetime）
- **新增接口**（AuthController / UserController）：
  - `POST /auth/refresh`（匿名放行）：实时查库校验用户存在且未禁用 → 轮换 → 返回新 token 对
  - `POST /auth/logout`（需登录）：删自己会话，Redis 异常降级成功
  - `POST /api/users/{id}/kick`（权限码 `user:kick`）：删目标会话；禁止踢自己（1022）/ 踢超管（1021）
- **禁用/解冻**：沿用 `PUT /api/users/{id}` 传 status（用户拍板不做独立接口）；补充保护——禁止禁用自己（1022）/ 超管（1020）；`delete()` 联动清理会话。禁用自带踢下线效果（access 实时查库 status → 401，refresh 拒绝续期）
- **权限码与 DB**：`init.sql` v1.7 预置 `user:kick` 并自动绑定 ADMIN（已验证），`PermissionConstants.USER_KICK`、`RoleConstants.ADMIN`、`ResultCode` 1019~1022、`SecurityConstants.REFRESH_URL` + `SecurityConfig` permitAll
- **测试**：新增 `AuthRefreshTest`（10 用例）、`UserKickTest`（9 用例）；`BaseIntegrationTest` 增加 `loginGetAuth`/`postJson`/`LoginSession` 与刷新会话清理
- **验证结果**：集成测试 **85/85 全绿**（66 存量 + 19 新增）；真实 HTTP 全链路验证通过（登录双 token → 轮换换新/旧失效 1019 → logout → 强踢后 refresh 1019、重登 200 → 禁用后 access 401/登录 1003 → 解冻重登 200 → 踢自己 1022 → 30d 上限 1019）
- **提交**：`cd73263`（26 文件，+920/-37），已推送 gitee master

## 三、错误与教训
- **技术坑 1（最深刻）**：`BaseIntegrationTest` 沿用 `userMapper.delete(null)` 清理用户——MyBatis-Plus 对带 `@TableLogic` 的实体 `delete(null)` 执行的是**逻辑删除 UPDATE**（把全表置 deleted=1）而非物理删除 → 污染预置 `admin@qsx.com`（被标 deleted=1 导致真实 HTTP 登录 1002）、用例间隔离失效。**改为 `JdbcTemplate` 物理 `DELETE FROM sys_user` / `sys_user_role`**。教训：测试清理用户表必须用原生 SQL 物理删除，带逻辑删除字段的 Mapper.delete(null) 会静默变成逻辑删除。
- **技术坑 2**：自建辅助方法 `loginGetAuth` 只登录不注册，新测试直接调用 → 用户不存在登录 1002（HTTP 200 + 业务码 1002）→ 解析出空会话 → 连锁断言失败，且失败表象（轮换 1019、selectOne NPE）极具迷惑性。教训：辅助方法更新后，所有调用点都要按新语义核查；「HTTP 200 + 业务码非 200」是 MockMvc 测试里最容易误以为成功的坑——`andExpect(isOk())` 拦不住业务失败。
- **技术坑 3**：保护区判断顺序——先判超管再判自己，导致「既是超管又是自己」返回 1020/1021 而非预期 1022；调整为**自我检查优先**（"禁止操作自己"是更普适的规则）。教训：多重拒绝规则要明确优先级，并用"同时命中多规则"的用例反推实现顺序。
- **流程失误**：PowerShell 不支持 bash heredoc（`git commit -m <<EOF` 解析失败）→ 改用多 `-m` 段落；`-Dtest=A,B` 逗号参数需加引号；`docker exec mysql sh -c` 嵌套引号在多语句时转义出错 → 改用 `docker exec mysql mysql ...` 直连执行。
- **吸取的教训**：测试基建的清理语义（物理 vs 逻辑删除）是隐患温床，改动依赖它前必须先确认；MockMvc 断言不能只看 HTTP 状态码，要看业务码。

## 四、关键技术抉择
- **refresh token 下发方式**：JSON 响应体（非 HttpOnly Cookie）——前后端分离改造成本最低，XSS 风险由前端防护承接（用户确认）
- **单端登录**：每用户单 refresh key，新登录覆盖旧会话——后台管理系统最常用、禁用清理最干净
- **滑动续期 + 绝对上限**：每次刷新重置 7d，自首次登录 30d 强制重登——防无限续期
- **禁用 = status 字段赋值**（用户拍板）：不做独立 disable/enable 接口、不与 refresh 联动——因为 access 每请求实时查库 + refresh 实时查 status，禁用天然实现"踢下线 + 禁续期 + 禁登录"
- **强踢 = 删 refresh 会话**：不立即失效对方 access（留 30min 窗口），需即时中断应上 access 黑名单（列为后续演进，避免每请求查 Redis 的性能代价）
- **会话链路 fail-closed vs 权限缓存 fail-open**：权限缓存是性能优化、可降级回源；refresh 是安全凭证，Redis 异常宁可拒绝续期也不放行（有意的安全取舍）
- **refresh token 存 SHA-256 哈希**而非明文，且非 JWT（随机串不可伪造/解析）
- **放弃**：权限变更时删 refresh（每请求实时加载权限，变更即生效，无需踢人）；refresh 设计成 JWT（没必要，随机串更简单）；多端登录矩阵（后台系统用不上）