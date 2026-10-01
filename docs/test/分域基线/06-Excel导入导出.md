# 06 · Excel 批量导入导出

> **📍 定位**：本文是 `docs/test/分域基线/` 下的**自动化用例分域索引**——只记「接口 ↔ 测试类 ↔ 断言点」这类**稳定**信息。
> **例数**见 [`../项目测试报告.md` §四.1](../项目测试报告.md)（唯一数据源，本文不重复记数）；
> **接口的权威行为**以 [`docs/design/05-业务功能与保护矩阵.md`](../../design/05-业务功能与保护矩阵.md) §6 与**当前源码**为准。
> **已对齐至** `b5c922b`（2026-10-01 核对，Track A 全绿）。

## 覆盖矩阵（接口 ↔ 权限 ↔ 测试类）

| 接口 | 权限 | 测试类 |
| :--- | :--- | :--- |
| `GET /api/users/import/template` | `user:import` | UserImportExportTest |
| `POST /api/users/import`（multipart，单次 ≤ 1000 行） | `user:import` | UserImportExportTest |
| `GET /api/users/export`（按筛选全量，单次 ≤ 50000 行） | `user:export` | UserImportExportTest |

## 测试类与断言点（`UserImportExportTest`）

| 关注点 | 断言 |
| :--- | :--- |
| 模板下载 | 200 + `attachment` + `spreadsheetml` 头 |
| 合法导入 | 200 `successCount=N`；默认口令 BCrypt 校验通过、昵称空则回退邮箱、`status` 正常、仅指定行绑角色 |
| 任一非法行 → **整批拒绝** | code **1017** `successCount=0` + `errors` 明细（库内重复 / 邮箱格式 / 角色不存在）；**合法行也不入库** |
| 文件内邮箱重复 | code **1017**，`errors` 含「重复」 |
| 含 **ADMIN 角色码** | code **1017**「禁止将内置超管角色分配给导入用户」（封自助提权） |
| 仅表头无数据行 | code **1017**「Excel 中没有可导入的数据行」 |
| 空白文件 / 错误扩展名 | code **400**（「上传文件不能为空」/「仅支持 .xlsx / .xls」） |
| 超出 1000 行 | code **1018** |
| 导出 | 200 + 可读回 `UserExportRow`（含角色码） |
| 导出筛选（`email` 参数） | 只返回匹配行 |
| 无 `user:import` / `user:export` 权限 | **HTTP 403** |

## 业务码落点

| 码 | 触发 |
| :--- | :--- |
| 400 | 文件为 0 字节 / 未上传；扩展名非 `.xlsx` `.xls`（**文件前置校验**） |
| 1017 | 有文件但无数据行 / 任一数据行非法（邮箱格式、已存在、**角色码不存在**、**含 ADMIN 角色码**、文件内邮箱重复）/ 含**公式单元格** / 解析失败（**整批拒绝 + errors 明细，含 Excel 行号**） |
| 1018 | 导入 > 1000 行；导出 > 50000 行 |

> **行号口径**：`errors` 里的行号 = `rowIndex + 1`（表头是第 1 行，数据从第 2 行起），
> 历史上曾因偏移一位排查半天。

## 设计要点与机制层断言

- **文件不落盘**：导入用 SAX 流式读输入流；导出/模板直接写响应流。
- **整批校验 + 整体拒绝**：任一行不合法则一行都不落库（直查 `sys_user` / `sys_user_role` 断言）。
- **一次查库**：先收集全部邮箱与角色编码，一次查已存在邮箱、一次查角色映射，避免 N+1。
- **默认口令**：`import.default-password`（本地默认 `qsx123456`，已外部化为 `${IMPORT_DEFAULT_PASSWORD:…}`），
  全批**复用同一个 BCrypt 哈希**（大批量性能取舍）。
- **导入账号一律 `must_change_password=1`**：口令仍是"一批账号共用的已知值"，但**首次登录后即失效**
  （登录本身仍 200，但该令牌只能访问 `/auth/**`，其余接口返回 **1037**）。
- ⚠️ **落库走 `UserMapper.insertBatch`——手写 `@Insert` + `<foreach>`，列清单是显式枚举的**：
  给 `User` 加字段时漏加该列**不会报错**（取 DB 默认值），是**静默失效**。
  `UserImportExportTest` 的直查断言同时充当该列清单的**守卫**。
- **文件名/内容异常不再回显实现细节**：解析异常只留服务端日志，对外是统一的
  「Excel 文件解析失败，请检查文件内容或格式；若表格中含公式等特殊单元格，请先粘贴为值后重试」。
- **公式单元格显式拒绝**：解析阶段逐格探测 `FormulaData` 并整批拒绝，
  避免「公式无缓存值时整格读成 null、被常规校验静默跳过」。
- **邮箱上限**：导入校验同样引用 `UserConstants.EMAIL_MAX = 100`。
- **并发兜底**：批量落库在插入处捕获 `DuplicateKeyException` 并映射回 **1017**。

## ⏳ 历史快照 · Track B（`f772da2`，2026-09-28 当轮 · Run A 默认配置）

> 以下是那一轮的**真实 HTTP 记录，不代表当前**；当前正向 / 反向 / 边界结果见总报告 §六~§八。

| # | 请求 | 结果 |
| --: | :--- | :--- |
| 1 | `GET /api/users/import/template`（admin） | **HTTP 200** · `…spreadsheetml.sheet` · ~3705 B · 头 `PK`（合法 xlsx） |
| 2 | `POST /api/users/import`（multipart，手工构造的 2 行 xlsx） | **HTTP 200** · `{"code":200,"data":{"successCount":2,"errors":[]}}` |
| 3 | 用默认口令登录导入的用户 | **HTTP 200** code 200（默认口令 + BCrypt 生效）——⚠️ 2026-09-29 起导入账号带 `must_change_password=1`，须先改密才能访问其它接口 |
| 4 | `GET /api/users/export`（admin） | **HTTP 200** · `…spreadsheetml.sheet` · ~3917 B · 头 `PK` |

> 上传的 xlsx 为**真实构造**（表头 `邮箱/昵称/状态/角色编码` + 2 行数据、含 1 行 `status=1`），
> 验证了「非单测生成」的真实二进制文件解析。

## 结论

Excel 模板下载 / 批量导入（整批校验拒绝 + 默认口令落库 + 强制首次改密）/ 条件导出三接口在双轨下全部符合预期，
真实二进制文件的上传与下载解析均正常；`400 / 1017 / 1018` 与无权限 403 均按设计拦截。
