# QSXManager - 会话总结（Excel 批量导入导出）

> 总结时间：2026-09-12 ｜ 来源：当前会话
> 项目硬规则见项目 memory（project_memory.md），本文件不重复抄写，仅记录本段工作。

## 〇、本次状态变化

- **新增**（相对上次 log 模块总结后，`5e655e0..843bfa5`，21 文件 +977/-4）：
  - 功能：`UserImportExportController` / `UserImportExportService(+Impl)` / `web/dto/excel/`（UserImportRow / UserExportRow / ImportResult）
  - 依赖：pom.xml 引入 `easyexcel:4.0.3`；application.yml 新增 multipart 10MB 限制与 `import.default-password`
  - 权限：`user:import` / `user:export` 权限码（PermissionConstants + ResultCode 1017/1018 + sql/init.sql 1.6 块，已对运行库执行增量 SQL）
  - Mapper：`UserMapper.insertBatch`（@Insert+foreach 批量插入）
  - 测试：`UserImportExportTest`（9 用例）；文档：README 更新 + 真实测试报告
- **调整**：`.trae/` 4 份文档迁移至 `docs/{design,plans,session-notes}`，`.gitignore` 忽略 `.trae/`
- **遗留事项变化**：Excel 导入导出闭环完成；后续规划仍为 Redis 缓存 / 邮箱验证码 / 前端界面
- **分支**：feature/user-excel-import-export 已合并（fast-forward）并推送 Gitee 后删除，本地仅剩 master

## 一、基础上下文

- **项目 / 主题**：QSXManager（Spring Boot 3.5.16 单体后台管理系统）— Excel 批量用户导入导出 + 文档目录整理
- **时间范围**：2026-09-12
- **涉及范围**：`user` 模块（认证/RBAC/菜单/日志既有功能 + 新增导入导出）、`docs/`、`.gitignore`、`sql/init.sql`

## 二、已完成任务

- **文档目录整理**：`.trae` 下 4 份文档迁至 `docs/`（design/plans/session-notes），`.gitignore` 忽略 `.trae`（commit `9bffd8f`）
- **Excel 批量导入导出**（commit `d6a09d3` feat / `e88110e` test / `843bfa5` docs）：
  - `GET /api/users/import/template`（模板下载，表头：邮箱/昵称/状态/角色编码）
  - `POST /api/users/import`（multipart 上传，整批校验+整体拒绝，失败返回 1017 + 错误行明细；通过后单事务批量插用户+角色关联）
  - `GET /api/users/export`（按筛选条件导出全量，不含密码，上限 5 万行，角色批量组装避免 N+1）
  - 默认密码 `qsx123456`（可配置），BCrypt 复用 hash（大批量性能考虑）
- **真实环境验证**（应用 8080 + curl，见 `docs/session-notes/qsxmanager/测试报告-qsxmanager-excel-20260912.md`）：7 个用例全过——登录 / 模板 / 导入合法(3行含角色) / 导入非法(1017 整批拒绝) / 导出全量(4行) / 导出筛选(1行) / 无权限 403
- **收尾**：全量 63 用例 0 失败；数据库重置预置状态（仅 admin@qsx.com + 28 权限 + 日志空）；分支合并推送 Gitee（`5e655e0..843bfa5`）；删除 feature 分支

## 三、错误与教训

- **技术坑：EasyExcel 行号偏移 1**
  - 现象：真实测试发现导入校验错误行号整体偏移 1（实际第 2 行报第 3 行）
  - 根因：EasyExcel 4.x 的 `ReadRowHolder.getRowIndex()` 在 `invoke` 时返回 0-based **物理行索引**（表头为 0、数据从 1 起），初版按逻辑行号 `+2` 多加了 1；集成测试只断言 `errors.length()` 未断言精确行号，未暴露
  - 修复：改为 `getRowIndex() + 1`，真实测试验证行号精确
- **流程失误：PowerShell 变量插值坑**：curl 请求头中直接写 `$resp.data.token` 被解析为 `$resp` + 字面量 `.data.token`，导致 401；改用标量变量 `$token` 后正常（与 memory 中"PowerShell 转义用反引号"同源教训：**多级属性访问在字符串插值里必须先赋给标量**）
- **吸取的教训**：① 涉及"用户可见的行号/序号"时，测试必须断言精确值而非仅存在性；② 真实 HTTP 冒烟测试能暴露单元/集成测试盲区，功能合流前值得做一遍

## 四、关键技术抉择

- **框架选型 EasyExcel 4.0.3**：注解驱动简洁、SAX 流式低内存、中文资料最全；虽已归档停更（2025-09），但导入导出属标准稳定场景，且与 Apache Fesod API 完全兼容（换包名即可迁移），风险可控。备选：POI 原生（繁琐）、Fesod（年轻资料少）
- **Excel 文件不落盘**：导入流式解析上传流、导出/模板直出响应流——无临时文件堆积、无磁盘清理、多实例无状态；未引入 SeaweedFS（当前无留存需求）
- **整批校验 + 整体拒绝**（用户选定）：全部行合法才单事务落库，否则返回全部错误明细，数据一致性强
- **导入默认密码**（用户选定）：统一 `qsx123456` 配置化，模板不含密码列；BCrypt 复用同 hash 换取 1000 行级导入性能
- **模板含角色编码列**（用户选定）：支持导入时绑定角色，角色不存在则整批拒绝
- **权限码新增 user:import / user:export**（用户选定）：细粒度授权，走 init.sql INSERT IGNORE + ADMIN 自动绑定既有机制
