# qsxmanager - 用户/会话双轨测试与会话总结

> 总结时间：2026-09-17 ｜ 来源：当前会话
> 读者导航：本次为「用户管理 + 会话管理」的再审→补保护→补测→双轨测试增量；想了解模块全貌读 `会话总结-qsxmanager全项目.md`，查双 token 机制原理见 `docs/design/用户与会话管理机制说明.md`、本次双轨结果见 `docs/test/用户与会话-真实HTTP测试报告.md`。

## 〇、本次状态变化

- **新增（未跟踪）**：
  - `src/test/java/com/qsx/UserEdgeTest.java`（15 个缺口用例）
  - `docs/design/用户与会话管理机制说明.md`
  - `docs/test/用户与会话-真实HTTP测试报告.md`、`docs/test/用户与会话-真实HTTP-api-results.json`
- **修改（已跟踪，未提交；delete 保护修复）**：
  - `src/main/java/com/qsx/common/result/ResultCode.java`（+`1025 内置超管用户不可删除`）
  - `src/main/java/com/qsx/service/impl/UserServiceImpl.java`（`delete()` 加删自己 1022 / 删超管 1025 保护）
  - `README.md`（错误码表 + 删除接口保护说明）
- **完成度变化**：用户管理保护规则补齐——`delete` 与 `update`(1020)、`kick`(1021) 完全对称（删除不可作用于超管/自己）；自动化 130/130；真实 HTTP 40+ 调用全过。
- **遗留事项变化**：解决「delete 无超管/自保保护」这一保护不对称；无新增遗留阻塞项。

## 一、基础上下文

- **项目 / 主题**：QSXManager 用户管理与会话管理
- **时间范围**：2026-09-17
- **涉及范围（文件 / 模块 / 分支）**：`UserController` / `UserServiceImpl` / `AuthSessionServiceImpl` / `ResultCode` 与 `src/test/java/com/qsx/*`；未创建新分支

## 二、已完成任务

1. **代码审查**：对用户管理与会话管理的流程、保护规则做审读，确认 delete 相比 update/kick **缺少超管保护**（保护不对称疑点）。
2. **修复 delete 保护**：`delete()` 启动即判——删自己→1022、删内置超管→1025（`ResultCode` 新增业务码，`README` 错误码表同步）。
3. **补自动化测试**：新增 `UserEdgeTest`（15 例）：创建参数校验、分页越界、assignRoles 边界(1001/1004/1009)与事务回滚、**空角色清空+权限即时回收**、删除会话完整断言、**delete 超管 1025 / 自保 1022**。
4. **机制说明文档**：`docs/design/用户与会话管理机制说明.md`（代码索引 / 会话三键模型 / 统一回收入口 / 用户管理接口表 / 保护矩阵 / 普通用户 vs 管理员边界 / 工程要点）。
5. **Maven 自动化**：`mvn -o test` **130/130 全绿**（含新增 UserEdgeTest）。
6. **真实 HTTP 双轨测试**：启动 `localhost:8080`，内置超管 `admin@qsx.com/admin123` 逐接口调 5 组 40+ 次全过：A 认证会话(B 单端/轮换/登出/改密 + Redis 三键 TTL 1800/604800s)、B 用户 CRUD、C 角色分配即时生效/回收、D 踢人即时失效、E 保护矩阵(1025/1022/1020/1021/401/403)。报告 + 原始 json 落盘 `docs/test/`。

## 三、错误与教训

- **流程失误（脚本 bug）**：PowerShell 的 `Req` 辅助函数体误把参数 `$token` 写成 `$at`，导致真实 HTTP 的 B5/B6 里「意图用目标用户 token」的断言实际误用了 admin token（B6 被删用户 at 一度显示 200）。
  - → 教训：脚本 helper 的**参数名与函数体引用必须一致**；关键断言用**正确的操作者/目标 token 复核**后才采信。
- **流程失误（登录态语义错位）**：A7 登出、E1 删超管，一开始误用已被刷新轮换作废的旧 at / 用 admin 删自己，导致先拿到 401/1022。改用「全新会话」「第二个 ADMIN 用户」才触发正确分支。
  - → 教训：验证「登出/轮换后失效」「超管保护」必须用**正确的操作者身份**与**有效令牌**。
- **技术坑（PowerShell 引号）**：PowerShell 不支持 `\"` 转义；`docker exec mysql -e` 多语句/未选库报 `ERROR 1046`。
  - → 教训：PowerShell 构造 JSON 一律用 `ConvertTo-Json -Compress`；MySQL 命令**显式指定 `QSXManager` 库名**且避免多语句。
- **流程失误（测试残留）**：被提升为 ADMIN 的测试用户无法用 HTTP 删除（正是保护在起作用），需用 SQL 清理 `email LIKE '%@test.com%'`。
  - → 教训：真实 HTTP 若提升用户到超管，收尾要 SQL 清库，不能依赖删除接口。

## 四、关键技术抉择

- **delete 保护口径**：按 `selectRoleCodes(...ADMIN)`（非只护内置 admin@qsx.com），与 kick/disable 完全一致 → 保证任何 ADMIN 角色用户都不可被删除/禁用/踢。
- **业务码定位**：`1025` 放 `ResultCode` 枚举数值尾部（1024 之后），不打断既有分组注释。
- **双轨报告独立文件**：新建 `docs/test/用户与会话-真实HTTP测试报告.md`，不动既有 `test-report.md`（用户选择独立专项报告）。
- **真实 HTTP 不留可复现脚本**：临时逐调 + 记录（用户偏好），仅落报告 + 原始 json。

---

*接续建议：本会话源码改动未提交，可待 CLAUDE.md 更新文档索引时一并 commit（如需提交请告知）。*