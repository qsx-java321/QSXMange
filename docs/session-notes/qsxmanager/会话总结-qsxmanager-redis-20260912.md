# QSXManager - 会话总结（RBAC 权限缓存 Redis）

> 总结时间：2026-09-12 ｜ 来源：当前会话（计划 → 实现 → 测试 → 提交全流程）

## 〇、本次状态变化

- **新增**：
  - RBAC 权限缓存核心：`service/PermissionCacheService(+Impl)`、`security/model/PermissionCacheData`、`config/properties/RbacCacheProperties`、`config/event/PermissionCacheEvictEvent(+Listener)`
  - 测试：`RbacCacheTest`、`RbacCacheDisabledTest`
  - 文档：`docs/plans/rbac-redis-cache-plan.md`、`docs/test/`（测试报告 test-report.md + api-test-results.json + run-api-test.ps1 + fix-data.sql）
- **修改**：`SecurityUserDetailsService`、`AuthServiceImpl`、`PermissionServiceImpl`、`UserServiceImpl`、`RoleServiceImpl`、`UserMapper/UserRoleMapper/RolePermissionMapper`、`pom.xml`、`application.yml`、`BaseIntegrationTest`、`README.md`
- **完成度变化**：认证→授权→业务→路由闭环之上新增权限缓存层；自动化 66 用例 + 真实 HTTP 38 调用全过；权限管理设计审查通过
- **遗留事项**：Token 黑名单、邮箱验证码、前端界面（未做）；测试基类全表逻辑删除会误伤预置 admin（数据已恢复，隐患仍在）

## 一、基础上下文

- **项目 / 主题**：QSXManager 后台管理系统 / RBAC 权限缓存（Redis）
- **时间范围**：2026-09-12
- **涉及范围**：`src/main`（security/service/config/mapper）15 个文件、`src/test` 3 个文件、`docs/`（plans/test/session-notes）、master 分支

## 二、已完成任务

1. **计划落盘**：`docs/plans/rbac-redis-cache-plan.md`，与用户确认 4 个决策（精确失效 / 仅缓存权限码 / 三处收口 / 落盘 plans）
2. **权限缓存实现**：Redis 优先 + 未命中回源 MySQL 并回填（含空集合防穿透）+ Redis 异常降级查库 + `enabled` 开关一键回退；key=`qsx:auth:perm:{userId}`
3. **三处读取点收口**：`UserDetailsService` / 登录 `LoginVO` / `getUserMenuTree` 统一走 `PermissionCacheService.load`
4. **失效链路**：`@TransactionalEventListener(AFTER_COMMIT)` 精确失效；`assignRoles/assignPermissions/角色删改/用户删除/菜单权限删除` 补 `@Transactional` 并发布事件（顺带修复整表替换非原子隐患）
5. **测试**：`RbacCacheTest`（回填/空权限防穿透/变更即时生效）、`RbacCacheDisabledTest`（降级）；`mvn test` 66/66 通过
6. **完整业务功能测试**：启动应用真实 HTTP 调用 38 次全部按预期（含 401/403 边界、Excel 导入整批拒绝明细），报告 `docs/test/test-report.md`
7. **数据修复**：预置中文乱码（30 处，utf8mb4 历史问题）、admin 被逻辑删除恢复（deleted=0 + 重建 ADMIN 关联 + 清缓存）、测试残留清理（`docs/test/fix-data.sql`）
8. **提交**：`baea6f7`（33 文件，含 session-notes 目录调整）

## 三、错误与教训

- **技术坑**：
  - PowerShell 5.1（`powershell`）按 ANSI 解码 UTF-8 脚本导致中文乱码、解析失败 → 改用 `pwsh` 执行
  - PS7 `Invoke-WebRequest -OutFile` 返回 null，文件接口元信息记录失败 → 改用 .NET HttpClient 同时拿响应头与字节
  - 测试基类 `userMapper.delete(null)` 全表逻辑删除 → 预置 admin 被删、`sys_user_role` 关联被清空 → 登录 1002/403（需恢复数据 + 重建关联 + 清权限缓存）
  - 历史 `init.sql` 导入未指定 `--default-character-set=utf8mb4` → 预置中文 mojibake（组件 README 已有警告）
- **流程失误**：docs 整理时把 `session-notes/qsxmanager/` 类别目录上移平铺，违反项目「按类别分文件夹」规则（wrap 技能揭示后已移回修正）
- **吸取的教训**：
  - 多行 git commit 用 PowerShell here-string（`@"..."@`），heredoc `<<'EOF'` 不可用
  - 给 docker exec 传 SQL 用管道（`"sql" | docker exec -i mysql ...`）或 docker cp，避免引号地狱
  - 动目录结构前先查项目既定组织规则（wrap 技能 / 旧文档），不要凭直觉平铺

## 四、关键技术抉择

| 决策 | 理由 |
|------|------|
| 缓存 key 用 userId 而非 email | 所有读取/失效点均持有 userId；改邮箱/禁用无需处理 key，无残留 |
| AFTER_COMMIT 事件失效而非同步删除 | 避免「事务提交前删缓存被旧数据回填覆盖」竞态 |
| 仅缓存权限码，用户行仍查库 | 密码/禁用状态实时生效，改动面小 |
| 空权限也回填 + TTL 兜底 + enabled 开关 | 防穿透 / 防失效失败永久陈旧 / 一键降级 |
| 测试基类 `adminToken()` 改走 `assignRoles` 真实链路 | 保证缓存失效事件生效，测试与生产行为一致 |
| 权限缓存异常降级查库 | 缓存是加速手段，不能成为故障点 |
