# 06 · Excel 批量导入导出

> 被测代码 `f772da2` · 生成 2026-09-28 · 本轮之前 Excel 属排除项，现已补测（双轨）。文件**不落盘**（导入用 SAX 流式读输入流，导出/模板直接写响应流）；**整批校验 + 整体拒绝**（任一行不合法则一行不落库）。设计见 `05-业务功能与保护矩阵.md` §6。

## 覆盖矩阵

| 接口 | 权限 | Track A | Track B |
| :--- | :--- | :--- | :--- |
| `GET /api/users/import/template` | `user:import` | UserImportExportTest | ✓ |
| `POST /api/users/import` | `user:import` | UserImportExportTest | ✓ |
| `GET /api/users/export` | `user:export` | UserImportExportTest | ✓ |

## Track A 结果（`UserImportExportTest` 9 例全绿）

| 关注点 | 断言 |
| :--- | :--- |
| 模板下载 | 200 + `attachment` + `spreadsheetml` |
| 合法导入（含 ADMIN 角色绑定） | 200 successCount=2；默认密码 BCrypt 校验通过、昵称空则回退邮箱、status 正常、仅指定行绑角色 |
| 任一非法行→整批拒绝 | code **1017** successCount=0 errors=3（库内重复/邮箱格式/角色不存在）；合法行也不入库 |
| 文件内邮箱重复 | code **1017** errors 含「重复」 |
| 空白文件 / 错误扩展名 | code **400** |
| 导出 | 200 + 可读回 `UserExportRow`（含角色码、含 ADMIN） |
| 导出筛选（`email` 参数） | 只返回匹配行 |
| 无权限（`user:import/export`） | **HTTP 403** |

## Track B 逐接口明细（真实 HTTP，Run A 默认配置）

| # | 请求 | 结果 |
| --: | :--- | :--- |
| 1 | `GET /api/users/import/template`（admin） | **HTTP 200** · `Content-Type: ...spreadsheetml.sheet` · 文件 ~3705 B · 头 `PK`（合法 xlsx） |
| 2 | `POST /api/users/import`（multipart，上传手工构造的 2 行 xlsx） | **HTTP 200** · `{"code":200,"data":{"successCount":2,"errors":[]}}` |
| 3 | 用默认口令登录导入的用户 | **HTTP 200** code 200（`qsx123456` + BCrypt 生效；nickname「导入用户一」入库） |
| 4 | `GET /api/users/export`（admin） | **HTTP 200** · `Content-Type: ...spreadsheetml.sheet` · 文件 ~3917 B · 头 `PK`（合法 xlsx） |

> 上传的 xlsx 为真实构造（表头 `邮箱/昵称/状态/角色编码` + 2 行数据；含 1 行 status=1 禁用），验证了「非单测生成」的真实二进制文件解析。

## 机制层

- 导入失败整批回滚、成功行与角色绑定关系一致（Track A 直查 `sys_user` / `sys_user_role` 断言）。
- 默认口令统一复用同一 BCrypt 哈希（大批量性能取舍）。

## 发现的问题 / 观察项

- 无新增缺陷。导出 `nickname` **未做公式注入转义**（`=HYPERLINK(...)` 开头会原样写出，见设计 05 §7 #5）；本轮未构造恶意昵称攻击，列为**既有已知缺口**待办。
- 导入无权限时 403、角色不存在整批拒绝，均按设计拦截。

## 结论

Excel 模板下载 / 批量导入（整批校验拒绝 + 默认口令落库 + 可登录）/ 条件导出三个接口在双轨下全部符合预期，真实二进制文件上传与下载解析均正常。