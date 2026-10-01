# 04 · 角色权限 RBAC

> **📍 定位**：本文是 `docs/test/分域基线/` 下的**自动化用例分域索引**——只记「接口 ↔ 测试类 ↔ 断言点」这类**稳定**信息。
> **例数**见 [`../项目测试报告.md` §四.1](../项目测试报告.md)（唯一数据源，本文不重复记数）；
> **接口的权威行为**以 [`docs/design/03-授权与RBAC.md`](../../design/03-授权与RBAC.md) /
> [`05-业务功能与保护矩阵.md`](../../design/05-业务功能与保护矩阵.md) 与**当前源码**为准。
> **已对齐至** `b5c922b`（2026-10-01 核对，Track A 全绿）。

## 覆盖矩阵（接口 ↔ 权限 ↔ 测试类）

| 接口 | 权限 | 测试类 |
| :--- | :--- | :--- |
| `GET /api/roles` | `role:page` | RoleTest / RbacTest |
| `GET /api/roles/all` | `role:page` | RoleTest |
| `GET /api/roles/{id}` | `role:get` | RoleTest |
| `POST /api/roles` | `role:create` | RoleTest |
| `PUT /api/roles/{id}` | `role:update` | RoleTest / AdminProtectionTest |
| `DELETE /api/roles/{id}` | `role:delete` | RoleTest / RbacCacheTest |
| `PUT /api/roles/{id}/permissions` | `role:assign` | RoleTest / RbacCacheTest |
| `GET /api/permissions` | `perm:page` | PermissionTest / RbacTest |
| `GET /api/permissions/all` | `perm:page` | PermissionTest |
| `GET /api/permissions/{id}` | `perm:get` | PermissionTest |

> 权限三接口**只读**：权限码维护走 `sql/init.sql`（新增需同步 `init.sql` + `PermissionConstants` + 控制器 `@PreAuthorize` 三处）。

## 测试类与断言点

| 测试类 | 关键断言点 |
| :--- | :--- |
| `RoleTest` | 分页/筛选、`/all` 仅启用、详情 `permissionIds`、1010（含逻辑删除行判重）、1009 各入口、1024、1026、1011、**1036**、删除级联、整表替换 |
| `PermissionTest` | 只读三接口、分页/筛选、**排序 tiebreaker**（`orderByAsc(id)`） |
| `RbacTest` | 角色权限 CRUD、分配、ADMIN 保护、整表替换、非法 `status` |
| `RbacCacheTest` | 缓存回填、防穿透（空权限也回填）、变更即时失效、删角色/权限后的失效回归 |
| `RbacCacheDisabledTest` | `qsx.rbac-cache.enabled=false` 时降级为实时查库 |

## 保护码落点（本域）

| 码 | 触发 |
| :--- | :--- |
| 1009 | 角色不存在（`GET/PUT/DELETE`、`PUT .../permissions`） |
| 1010 | `POST /api/roles` 编码重复（**判重包含逻辑删除行**，`countByCodeIncludeDeleted`） |
| 1011 | `DELETE /api/roles/{id}` 目标为内置 ADMIN |
| 1012 | `PUT /api/roles/{id}/permissions` 的 `permissionIds` 含不存在的权限 |
| 1024 | `PUT /api/roles/{id}` **改 code**（标识创建后不可修改） |
| 1026 | `PUT /api/roles/{id}` 停用内置 ADMIN |
| 1035 | 删除系统内置菜单 / 权限行（保护集合 = 29 个预置码，见 [05](05-菜单与操作日志.md)） |
| **1036** | `PUT /api/roles/{id}/permissions` 目标为内置 ADMIN——**空列表与幂等重存一律拒绝** |

> **`PUT /api/roles/{id}/permissions` 优先级**：`1009（角色不存在）→ 1036（内置 ADMIN）→ 1012（权限不存在）`。
> 1036 位置在「反查受影响用户」之前：被拒请求不产生任何副作用（不跑 DISTINCT 查询、不动关联表、
> 更不会发布 `AFTER_COMMIT` 的缓存失效事件）。

## 机制层断言

- 缓存 Key `qsx:auth:perm:{userId}` 在角色/权限变更**事务提交后**删除
  （`PermissionCacheEvictEvent` → `@TransactionalEventListener(AFTER_COMMIT)`），
  保证「已回收权限不再生效至 TTL 到期」。
- **受影响用户必须在改动关联表之前反查**（监听器在提交后执行，届时关联行已删除、反查必为空）。
- 停用角色按 `r.status = 0` 过滤授权 + 缓存精确失效，构成双闸；**身份判定**（是否持有 ADMIN）
  走 `existsRoleCode`（不过滤 `status`），故 ADMIN 停用后保护依然成立。
- `POST /api/roles` 在 INSERT 处捕获 `DuplicateKeyException` 映射回 **1010**。

## ⏳ 历史快照 · Track B（`f772da2`，2026-09-28 当轮）

> 以下是那一轮的**真实 HTTP 记录，不代表当前**；当前正向 / 反向 / 边界结果见总报告 §六~§八。

| # | 请求 | 结果 |
| --: | :--- | :--- |
| 1 | `POST /api/roles` `{code,name,status:0}` | 200 code 200（返回 id） |
| 2 | 复用同 code 再建 | 200 code **1010** |
| 3 | `GET /api/roles?page=1&size=10` | 200 code 200 |
| 4 | `GET /api/roles/all` | 200 code 200（首条 ADMIN，仅启用） |
| 5 | `GET /api/roles/{id}` | 200 code 200 |
| 6 | `PUT /api/roles/{id}`（改名，code 不变） | 200 code 200 |
| 7 | `PUT /api/roles/{id}`（**改 code**） | 200 code **1024** |
| 8 | `PUT /api/roles/1`（停用内置 ADMIN） | 200 code **1026** |
| 9 | `DELETE /api/roles/1`（删内置 ADMIN） | 200 code **1011** |
| 10 | `GET /api/permissions?page=1&size=10` | 200 code 200 |
| 11 | `GET /api/permissions/all` | 200 code 200 |
| 12 | `GET /api/permissions/{id}` | 200 code 200 |
| 13 | `PUT /api/roles/{id}/permissions` | 200 code 200 |
| 14 | `DELETE /api/roles/{id}`（普通角色） | 200 code 200（级联解除关联） |

> 该轮记录不含 1036（内置超管角色权限不可改，2026-09-29 才落地）；
> 1035（内置菜单/权限不可删）也在同批新增。两者由 `RoleTest` / `MenuTest` 覆盖，
> 并经 2026-10-01 真实 HTTP 复核（见总报告 §七 7.4）。

## 结论

角色 CRUD、权限只读、整表替换、编码唯一/不可改、内置角色保护，以及
`1011` / `1024` / `1026` / **`1036`** / **`1035`** 在双轨下全部符合预期；权限缓存失效机制（提交后精确失效）已验证。
