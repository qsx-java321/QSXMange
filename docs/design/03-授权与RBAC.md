# 03 · 授权与 RBAC

## 1. 数据模型：五张表 + 一表两用

```
sys_user ──< sys_user_role >── sys_role ──< sys_role_permission >── sys_permission
                                                                      └─ parent_id 自关联成树
```

| 表 | 说明 |
| :--- | :--- |
| `sys_user` | 用户；`email` 唯一索引；`status`(0 正常 / 1 禁用)；逻辑删除 |
| `sys_role` | 角色；`code` 唯一索引（如 `ADMIN`）；`status`(0 启用 / 1 停用)；逻辑删除 |
| `sys_permission` | **菜单与按钮权限共用一表**，`type` 区分 `MENU` / `PERMISSION`；`code` 唯一索引；`parent_id` 成树 |
| `sys_user_role` | 用户-角色关联；**纯关系表，物理删除，整表替换语义** |
| `sys_role_permission` | 角色-权限关联；同上 |

**为什么菜单与权限共用一表**：菜单本身也是一种「看得见的权限」——前端的动态路由与按钮显隐，
和接口的 `@PreAuthorize` 用的是同一套 `code`。共用一表让「给角色授权」这一个动作同时决定
「能调什么接口」与「能看到什么菜单」，不需要两套模型对齐。

预置数据（`sql/init.sql`，幂等 `INSERT IGNORE`）：6 个菜单 + 23 个按钮权限码 + `ADMIN` 角色
（绑定全部菜单与权限）+ 超管账号 `admin@qsx.com / admin123`。

> 该超管种子带 `must_change_password = 1`：首次登录后必须先改密才能访问 `/auth/**` 以外的
> 接口（业务码 1037），见 [05](05-业务功能与保护矩阵.md) §8。`admin123` 是本地公开的**临时**口令。

## 2. 鉴权方式：权限码 + `@PreAuthorize`

```java
@PreAuthorize("hasAuthority('" + PermissionConstants.USER_CREATE + "')")
```

- **ADMIN 超管不是硬编码绕过**，而是「角色绑定了全部权限码」——与普通角色走完全相同的判定路径。
  好处：权限码清单、缓存、失效逻辑都不需要为超管开特例。
- **URL 级 + 方法级双保险**：`anyRequest().authenticated()` 兜底，方法上再用权限码限定。
- 登录返回的 `permissions` 同时给前端用于按钮显隐，与后端判定同源（同一份缓存数据）。

### 新增一个权限码要同步三处

| # | 位置 | 做什么 |
| :-- | :--- | :--- |
| 1 | `sql/init.sql` | `INSERT IGNORE` 预置权限行 + 自动绑到 ADMIN（幂等，重建库不会重复） |
| 2 | `PermissionConstants` | 加常量（`qsx-module-system`） |
| 3 | 控制器方法 | 加 `@PreAuthorize("hasAuthority('...')")` |

漏掉第 1 步的症状：接口存在但 ADMIN 也没权限（403）；漏掉第 3 步：接口对所有登录用户开放。

## 3. 权限缓存

| 项 | 值 |
| :--- | :--- |
| Key | `qsx:auth:perm:{userId}`（**用 userId 而非 email**：读取与失效点都持有 userId，改邮箱无需处理 key） |
| Value | `PermissionCacheData` 的 JSON：`{roles:[], permissions:[]}` |
| TTL | `qsx.rbac-cache.ttl`，默认 30 分钟（**仅兜底**，主动失效优先） |
| 开关 | `qsx.rbac-cache.enabled=false` 时退化为实时查库，`evict` 变空操作 |

三个设计点：

- **只缓存权限码**：用户行（密码、状态、删除标记）永远实时查库，因此改密/禁用/删除即时生效。
- **空权限也回填**：没有角色的用户同样写一份空集合，防止「未绑定角色的用户每请求回源」的穿透。
- **fail-open**：Redis 异常降级实时查库，不中断业务（与 §02 的会话 fail-closed 形成分级）。

### 失效约定（改这块之前必须读）

```
写事务内 publishEvent(PermissionCacheEvictEvent.ofUsers(userIds))
   → @TransactionalEventListener(AFTER_COMMIT)
   → 删 qsx:auth:perm:{userId}
```

**关键约束：受影响用户必须在改动关联表（`sys_user_role` / `sys_role_permission`）之前反查。**
监听器在提交后才执行，届时关联行已被删除、反查必然为空——这曾导致「**已回收的权限仍生效至 TTL 到期**」。
反查方法见 `UserRoleMapper.selectUserIdsByRoleId` / `selectUserIdsByPermissionId`。

**另一个坑：无事务时发布的事件会被静默丢弃**。`@TransactionalEventListener` 默认
`fallbackExecution=false`，所以发布方必须真的在事务里（`RoleServiceImpl` / `PermissionServiceImpl` /
`UserServiceImpl` 的相关方法都带 `@Transactional`）。

### 已知的有界陈旧（有意取舍）

「未命中回填」与「提交后失效」之间存在毫秒级竞态：一个在提交前读到旧数据的在途请求，
仍可能把旧权限写回缓存，最长残留一个 TTL（30 分钟）。后台管理并发极低，
此处选择以 TTL 兜底而非引入版本号；若需严格保证，可为缓存键加版本号并做回填 CAS。

## 4. 停用角色即时回收权限

权限查询按 `r.status = 0` 过滤（`UserMapper.selectPermissionCodes`），并且
`RoleServiceImpl.update` 会在事务提交后主动失效该角色下全部用户的缓存。两者缺一不可：

- 只过滤状态、不失效缓存 → 已缓存的权限码还能用到 TTL 到期；
- 只失效缓存、不过滤状态 → 回源查出来的还是同样的权限，停用形同虚设。

`GET /api/roles/all` 等下拉同样只列启用角色。

## 5. 身份判定 vs 授权判定（本项目最贵的一次教训）

同一个「用户有什么角色」的问题，在本项目里由**两条不同的查询**回答：

| 查询 | 语义 | 过滤 `r.status`？ |
| :--- | :--- | :--- |
| `UserMapper.selectRoleCodes` | 「该用户**实际拥有**哪些权限」（授权判定） | **过滤**（停用角色不再授权） |
| `UserMapper.existsRoleCode` | 「该用户**是不是**某个内置身份」（身份判定） | **刻意不过滤**（身份不因角色停用而改变） |

**事故经过**：超管的三条保护（1020 不可禁用 / 1021 不可强制登出 / 1025 不可删除）曾统一用
`selectRoleCodes(id).contains("ADMIN")` 做身份判定——而这条查询带 `AND r.status = 0`。
于是 **ADMIN 角色一旦被停用，该查询返回空集，三条保护同时静默失效**，操作者随即可以
禁用/踢掉/删除超管账号（实测确认：回退修复后三个接口均返回 200，即操作真的成功了）。

**修复是两道闸**：

1. `existsRoleCode`（不过滤状态）承担身份判定，与授权查询严格分工；
2. `RoleServiceImpl.update` 拦截停用内置 ADMIN 角色（**1026**）——停用后所有「仅经该角色获得权限」
   的用户立刻失去 `role:update` / `role:assign`，若无人另有权限来源，系统就再没人能把它启用回来，
   只能改库恢复。

> 这类缺陷的通式：**判定谓词必须与它要回答的问题同源**。复用一条为别的语义设计的查询，
> 错位处就是保护缺口。（另一次同类事故见 §02 的 `isDisabling`：必须全链路统一「非 0 即禁用」。）

## 6. 标识（code）与角色编码：创建后不可改、删除后不可复用

**不可修改**（1023 / 1024）：`code` 是鉴权依据，改错会让 `@PreAuthorize` 全线失配，
**包括把它改回来所需的那个接口**，连界面都救不回来。角色编码还关系到内置超管保护
（按 `RoleConstants.ADMIN` 匹配），改名等于绕过保护。

**不可复用**：`uk_role_code` / `uk_perm_code` 都是**物理**唯一索引（不含 `deleted` 列），
而删除是逻辑删除——已删行仍占用该编码。两种索引冲突的解法在本项目里是**两套策略**：

| 对象 | 策略 | 理由 |
| :--- | :--- | :--- |
| 角色编码 / 权限标识 | **删除即烧毁编码**（判重刻意包含逻辑删除行，`countByCodeIncludeDeleted`） | 编码是鉴权键，复活旧编码会让「历史遗留的关联行」重新获得意义 |
| 用户邮箱 | **删除时把 email 改写为 `#deleted_<时间戳>` 释放索引** | 邮箱是用户的身份标识，用户被删后同一邮箱应能重新注册 |

如果不做判重包含已删行，症状是：删除后用同一编码新建 → 校验通过 → INSERT 撞唯一索引 →
返回 **500 而不是 1010/1016**。

## 7. 保护矩阵（所有「内置 / 自己」相关的拦截）

| 码 | 含义 | 触发点 |
| :--- | :--- | :--- |
| 1011 | 内置超管角色不可删除 | `DELETE /api/roles/{id}` |
| 1020 | 内置超管用户不可禁用 | `PUT /api/users/{id}` 且 status≠0 |
| 1021 | 内置超管用户不可强制登出 | `POST /api/users/{id}/kick` |
| 1022 | 不允许对自己执行该操作 | 禁用自己 / 删除自己 / 踢自己 |
| 1023 | 菜单或权限标识创建后不可修改 | `PUT /api/menus/{id}` 改 code |
| 1024 | 角色编码创建后不可修改 | `PUT /api/roles/{id}` 改 code |
| 1025 | 内置超管用户不可删除 | `DELETE /api/users/{id}` |
| 1026 | 内置超管角色不可停用 | `PUT /api/roles/{id}` 且 status≠0 |

> 这张表的**缺口**（例如「可以给内置超管清空角色」「权限行删除无保护」）如实记录在
> [05-业务功能与保护矩阵.md](05-业务功能与保护矩阵.md) 的「已知缺口」一节。

## 8. 动态菜单

`GET /api/menus/current`（登录即可）返回当前用户的菜单树，供前端做动态路由：

1. 取用户权限码（走缓存）；
2. 从全量 `type=MENU` 中筛出「用户有权限的菜单」；
3. **补齐祖先链**——否则有子菜单权限却看不到父级入口；
4. 按 `parent_id` 组装成树，保持 `sort` 顺序。

管理端的 `GET /api/menus/tree` 返回全量树（含按钮权限），需要 `menu:tree` 权限。
菜单的增删改有两条硬规则：**防环**（父节点不能指向自身或子孙）与**有子节点禁止删除**。
