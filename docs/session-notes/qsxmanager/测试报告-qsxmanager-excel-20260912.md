# QSXManager 完整测试报告（Excel 批量导入导出）

> 测试日期：2026-09-12
> 测试方式：真实启动应用 + 真实 HTTP 调用（curl / Invoke-RestMethod）+ 数据库核验 + EasyExcel 读回校验
> 分支：feature/user-excel-import-export

## 一、测试环境

| 项 | 值 |
|---|---|
| 应用 | QSXManager，`mvn spring-boot:run`，端口 8080 |
| 数据库 | MySQL 8.4（Docker 容器 mysql），库 `QSXManager`，已重跑 `sql/init.sql` 重置为预置状态 |
| 预置超管 | `admin@qsx.com / admin123`（ADMIN 角色，绑定全部 28 个权限码） |
| 测试文件 | `target/excel-test/`（valid-users.xlsx 合法 3 行 / invalid-users.xlsx 非法 3 行+合法 1 行） |

## 二、测试用例与结果

### 用例 1：超管登录

- **URL**：`POST http://localhost:8080/auth/login`
- **请求体**：`{"email":"admin@qsx.com","password":"admin123"}`
- **请求头**：`Content-Type: application/json`
- **响应**（code 200）：

```json
{
  "code": 200, "message": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzM4NCJ9...",
    "userId": 1, "email": "admin@qsx.com", "nickname": "超级管理员",
    "roles": ["ADMIN"],
    "permissions": [ "system", "system-user", ..., "user:import", "user:export" ]
  }
}
```

- **结论**：✅ 登录成功，token 携带全部权限码（含新增的 `user:import`/`user:export`）

### 用例 2：下载导入模板

- **URL**：`GET http://localhost:8080/api/users/import/template`
- **请求头**：`Authorization: Bearer <token>`
- **响应**：HTTP 200，下载 `template-download.xlsx`（3704 字节），`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition: attachment`
- **结论**：✅ 模板正常下载

### 用例 3：批量导入合法文件

- **URL**：`POST http://localhost:8080/api/users/import`
- **请求头**：`Authorization: Bearer <token>`
- **请求体**：`multipart/form-data`，字段 `file` = valid-users.xlsx（3 行：001 带 ADMIN 角色 / 002 禁用+空昵称 / 003 正常无角色）
- **响应**：

```json
{"code":200,"message":"操作成功","data":{"successCount":3,"errors":[]}}
```

- **数据库核验**：

| id | email | nickname | status | roles |
|---|---|---|---|---|
| 1 | admin@qsx.com | 超级管理员 | 0 | ADMIN |
| 2 | exeltest-001@test.com | 测试用户一 | 0 | ADMIN |
| 3 | exeltest-002@test.com | exeltest-002@test.com（空昵称回退邮箱） | 1（禁用） | NULL |
| 4 | exeltest-003@test.com | 测试用户三 | 0 | NULL |

- **结论**：✅ 3 行全部入库，默认密码 BCrypt 加密，角色绑定正确，空昵称回退邮箱，状态映射正确

### 用例 4：批量导入非法文件（整批拒绝）

- **URL**：`POST http://localhost:8080/api/users/import`
- **请求体**：`multipart/form-data`，字段 `file` = invalid-users.xlsx（第 2 行 admin@qsx.com 库内重复 / 第 3 行 bad-email 格式错误 / 第 4 行 NO_SUCH_ROLE 角色不存在 / 第 5 行合法行）
- **响应**：

```json
{"code":1017,"message":"导入数据校验失败","data":{"successCount":0,"errors":[
  "第 2 行：邮箱已存在：admin@qsx.com",
  "第 3 行：邮箱格式不正确",
  "第 4 行：角色编码不存在：NO_SUCH_ROLE"
]}}
```

- **结论**：✅ 整批拒绝（successCount=0），合法行也未入库，错误明细含精确行号与原因

### 用例 5：导出全量用户

- **URL**：`GET http://localhost:8080/api/users/export`
- **请求头**：`Authorization: Bearer <token>`
- **响应**：HTTP 200，下载 `export-all.xlsx`（3959 字节）
- **读回校验**（EasyExcel 解析导出文件，共 4 行）：

| 邮箱 | 状态 | 角色编码 | 创建时间 |
|---|---|---|---|
| admin@qsx.com | 正常 | ADMIN | （预置数据无） |
| exeltest-001@test.com | 正常 | ADMIN | 2026-09-12 05:07:24 |
| exeltest-002@test.com | 禁用 | | 2026-09-12 05:07:24 |
| exeltest-003@test.com | 正常 | | 2026-09-12 05:07:24 |

- **结论**：✅ 导出内容与库一致，状态/角色列正确，**不含任何密码字段**

### 用例 6：导出按条件筛选

- **URL**：`GET http://localhost:8080/api/users/export?email=exeltest-001%40test.com`
- **响应**：HTTP 200，`export-filter.xlsx`（3827 字节），读回 1 行且 email 精确匹配
- **结论**：✅ 筛选条件生效

### 用例 7：无权限访问拦截

- **前置**：注册普通用户 `exeltest-noperm@test.com`（无任何角色，权限数为 0）
- **URL**：`GET /api/users/export`、`POST /api/users/import`（携带普通用户 token）
- **响应**：均 HTTP 403
- **结论**：✅ `@PreAuthorize` 权限码校验生效

## 三、测试中发现并修复的问题

**问题**：导入校验的错误行号整体偏移 1 位（实际第 2 行报为第 3 行）。
**根因**：EasyExcel 4.x 的 `ReadRowHolder.getRowIndex()` 在 `invoke` 时返回 0-based **物理行索引**（表头为 0、数据行从 1 起），原实现按"逻辑数据行索引"计算 `+2`，多加了 1。
**修复**：[UserImportExportServiceImpl.java](file:///d:/Java_SE/Git/QSXManager/src/main/java/com/qsx/service/impl/UserImportExportServiceImpl.java) 行号计算改为 `getRowIndex() + 1`，注释同步更正。
**回归验证**：修复后重测非法导入，行号准确（第 2/3/4 行）；自动化集成测试（UserImportExportTest 9 用例）全量通过。

## 四、测试结论

| 维度 | 结果 |
|---|---|
| 功能完整性 | ✅ 模板下载 / 导入（成功+整批拒绝）/ 导出（全量+筛选）全部符合预期 |
| 数据一致性 | ✅ 整批拒绝不落任何数据；导入后角色绑定、状态、默认密码正确 |
| 权限控制 | ✅ 无 `user:import`/`user:export` 权限的用户返回 403 |
| 安全 | ✅ 导出不含密码；文件扩展名白名单；行号精确指引修正 |
| 自动化回归 | ✅ 全量 63 个用例（含新增 9 个）0 失败 0 错误 |

**总体结论**：Excel 批量导入导出功能真实环境验证通过。测试后数据库已清理测试数据并恢复预置状态（仅保留超管 `admin@qsx.com`），应用已停止、8080 端口释放。
