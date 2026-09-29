# 04 · 角色权限 RBAC

> 被测代码 `f772da2` · 生成 2026-09-28 · 覆盖：角色 7 接口 + 权限 3 接口（只读）。权限码维护走 `sql/init.sql`、权限缓存失效、1023/1024/1026 等详见设计文档 `03-授权与RBAC.md` / `05-业务功能与保护矩阵.md`。

## 覆盖矩阵

| 接口 | 权限 | Track A | Track B |
| :--- | :--- | :--- | :--- |
| `GET /api/roles` | `role:page` | RoleTest / RbacTest | ✓ |
| `GET /api/roles/all` | `role:page` | RoleTest | ✓（仅启用） |
| `GET /api/roles/{id}` | `role:get` | RoleTest | ✓ |
| `POST /api/roles` | `role:create` | RoleTest | ✓（含重复 1010） |
| `PUT /api/roles/{id}` | `role:update` | RoleTest / AdminProtectionTest | ✓（改码 1024、停用 ADMIN 1026） |
| `DELETE /api/roles/{id}` | `role:delete` | RoleTest / RbacCacheTest | ✓（删 ADMIN 1011） |
| `PUT /api/roles/{id}/permissions` | `role:assign` | RoleTest / RbacCacheTest | ✓ |
| `GET /api/permissions` | `perm:page` | PermissionTest / RbacTest | ✓ |
| `GET /api/permissions/all` | `perm:page` | PermissionTest | ✓ |
| `GET /api/permissions/{id}` | `perm:get` | PermissionTest | ✓ |

## Track A 结果（本域 35 例，全绿）

| 测试类 | 例数 | 关键断言点 |
| :--- | ---: | :--- |
| `RoleTest` | 16 | 分页/筛选、`/all` 仅启用、详情 permissionIds、1010、四入口 1009、1024、1026、1011、删除级联、整表替换 |
| `PermissionTest` | 5 | 只读三接口、分页/筛选 |
| `RbacTest` | 7 | 角色权限 CRUD、分配、ADMIN 保护、整表替换、非法 status |
| `RbacCacheTest` + `RbacCacheDisabledTest` | 6 + 1 | 缓存回填、防穿透、变更即时失效、删除角色/权限后失效回归、开关降级 |

## Track B 逐接口明细（真实 HTTP）

| # | 请求 | 结果 |
| --: | :--- | :--- |
| 1 | `POST /api/roles` `{code:'tbr_...',name,status:0}` | 200 code 200（返回 id） |
| 2 | 复用同 code 再建 | 200 code **1010**「角色编码已存在」 |
| 3 | `GET /api/roles?page=1&size=10` | 200 code 200 |
| 4 | `GET /api/roles/all` | 200 code 200（首条 ADMIN，仅启用） |
| 5 | `GET /api/roles/{id}` | 200 code 200 |
| 6 | `PUT /api/roles/{id}`（改名，code 不变） | 200 code 200 |
| 7 | `PUT /api/roles/{id}`（**改 code**） | 200 code **1024**「角色编码创建后不可修改」 |
| 8 | `PUT /api/roles/1`（停用内置 ADMIN） | 200 code **1026** |
| 9 | `DELETE /api/roles/1`（删内置 ADMIN） | 200 code **1011** |
| 10 | `GET /api/permissions?page=1&size=10` | 200 code 200 |
| 11 | `GET /api/permissions/all` | 200 code 200 |
| 12 | `GET /api/permissions/{id}` | 200 code 200 |
| 13 | `PUT /api/roles/{id}/permissions` `{permissionIds:[...]}` | 200 code 200 |
| 14 | `DELETE /api/roles/{id}`（普通角色） | 200 code 200（级联解除关联） |

## 机制层断言

- 缓存 Key `qsx:auth:perm:{userId}` 在角色/权限变更事务提交后删除（Track A `RbacCacheTest` 直查 Redis 断言），「已回收权限不再生效到 TTL」。
- 停用角色按 `r.status=0` 过滤授权 + 缓存精确失效双闸（Track A）。

## 发现的问题 / 观察项

- 无新增缺陷。`1024`（编码创建后不可改）、`1026`（不可停用 ADMIN）、`1011`（不可删 ADMIN）均按设计拦截。
- ~~已知缺口：删除权限行无保护~~ **已于 2026-09-29 修复**（`35a2c2e`，业务码 1035）：
  系统内置的 29 个预置菜单/权限码一律不可删，`BUILT_IN_CODES` 与 `init.sql` 有同步守卫测试。

## 结论

角色 CRUD、权限只读、整表替换、编码唯一/不可改、内置角色保护在双轨下全部符合预期，权限缓存失效机制已验证。