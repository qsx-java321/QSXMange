# RBAC 权限管理实现计划（分支 `rbac`）

## Context（背景与目标）

当前 QSXManager 基础版仅有「用户 CRUD + 注册/登录 JWT」，认证为纯 JWT 无状态，`SecurityUser.getAuthorities()` 返回空集合，`sys_user` 是唯一数据表。本任务新开分支 `rbac`，在现有用户管理之上实现**完整的 RBAC 权限管理设计**（权限码模型 + Spring Security 方法级鉴权），共 5 张标准表。非权限相关功能（日志、Redis 缓存、邮箱验证码等）本分支不做。

已与用户确认的决策：
- **权限模型**：权限码模型 —— `sys_permission` 存权限标识（如 `user:add`），配合 `@PreAuthorize("hasAuthority('...')")`，不做菜单树。
- **加载策略**：每请求查库，暂不引入 Redis，权限变更即时生效。
- **初始化**：预置「admin 角色绑定全部权限 + admin 账号」，便于直接上手测试；超管用「绑定全部权限」实现，不做硬编码 bypass。

现有技术底座（已核实）：Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Spring Security（已开 `@EnableMethodSecurity`）+ JWT(jjwt HS384)，MySQL 8.4 Docker（localhost:3306/QSXManager，root/123456）。`JwtAuthenticationFilter` 每次请求已调用 `loadUserByUsername` 并把 `getAuthorities()` 写入 `Authentication`，因此只要让 `SecurityUserDetailsService` 查角色/权限、`SecurityUser.getAuthorities()` 返回它们，**授权链路由土复用，JWT 过滤器无需改动**。

---

## 一、5 张表设计（`sql/init.sql`，在 `sys_user` 后追加）

1. `sys_user`（已有，结构不变）
2. `sys_role`：`id、code(VARCHAR64 唯一)、name、description、status(0启用/1停用)、create_time、update_time、deleted`
3. `sys_permission`：`id、code(VARCHAR64 唯一)、name、type(默认 PERMISSION)、sort、create_time、update_time、deleted`
4. `sys_user_role`：关系表，`id 自增 + UNIQUE(user_id,role_id) + KEY(role_id) + create_time`，**不加 deleted**
5. `sys_role_permission`：关系表，`id 自增 + UNIQUE(role_id,permission_id) + KEY(permission_id) + create_time`，**不加 deleted**

> **关系表取舍**：角色/权限分配用「整表替换」语义（先物理删旧行再批量插新行），需物理删除、无需历史追溯，故两张纯关系表**不继承 BaseEntity、不加 `deleted` 逻辑删除字段**，从而让 MyBatis-Plus 在其上做真实物理删除。保留自增 `id` 仅为兼容 `BaseMapper.insert/delete`。带逻辑删除的主表（role/permission）风格与 `sys_user` 一致（engine=InnoDB, charset=utf8mb4, 双时间字段）。

DDL 可参考现有 `sys_user` 注释/风格；`sys_role.code`、`sys_permission.code` 均建唯一索引。

### 种子数据（插入顺序：权限 → 角色 → 角色-权限 → 用户 → 用户-角色）

- 全量权限码 INSERT（14 条，见第三节清单），`deleted=0`。
- admin 角色：`code='ADMIN'`，名称「超级管理员」。
- 角色-权限全量：`INSERT INTO sys_role_permission(role_id,permission_id) SELECT (SELECT id FROM sys_role WHERE code='ADMIN'), id FROM sys_permission;`（幂等可用）。
- admin 账号：`admin@qsx.com`，密码 BCrypt（默认 `admin123`，init.sql 注释标明默认密码与替换方法）。
- 用户-角色：`SELECT (SELECT id FROM sys_user WHERE email='admin@qsx.com'), (SELECT id FROM sys_role WHERE code='ADMIN')`。

> 说明：init.sql 中 BCrypt hash 无法离线保证与密码一致，标注「默认密码 admin123，可登录后改密或用 BCryptPasswordEncoder 生成 hash 替换」。自动化测试**不依赖该 hash**（见第七节用「注册+绑 ADMIN 角色」自造超管）。

---

## 二、领域实体与 Mapper（新建）

**实体**（`com.qsx.domain.entity`，遵循现有 `@Data`/`@TableName` 风格）：
- `Role extends BaseEntity`：code、name、description、status
- `Permission extends BaseEntity`：code、name、type、sort
- `UserRole`（**不继承 BaseEntity**）：`@TableId Long id; Long userId; Long roleId; LocalDateTime createTime;`
- `RolePermission`（**不继承 BaseEntity**）：`Long id; Long roleId; Long permissionId; LocalDateTime createTime;`

**Mapper**（`com.qsx.mapper`，均 `@Mapper extends BaseMapper`）：
- `RoleMapper`、`PermissionMapper`、`UserRoleMapper`、`RolePermissionMapper`
- `RolePermissionMapper.insertBatch(List)` 与 `UserRoleMapper.insertBatch(List)`：`@Insert("<script>INSERT INTO ... VALUES <foreach>...</foreach></script>")`，供「整表替换」批量插入。

**权限/角色加载查询**：追加到 `UserMapper`（复用已注入 Bean，避免引 XML，项目无 XML 先例）：

```java
@Select("SELECT r.code FROM sys_user_role ur JOIN sys_role r ON ur.role_id=r.id WHERE ur.user_id=#{userId} AND r.deleted=0")
List<String> selectRoleCodes(@Param("userId") Long userId);

@Select("SELECT DISTINCT p.code FROM sys_user_role ur JOIN sys_role r ON ur.role_id=r.id AND r.deleted=0 " +
        "JOIN sys_role_permission rp ON rp.role_id=r.id JOIN sys_permission p ON rp.permission_id=p.id AND p.deleted=0 " +
        "WHERE ur.user_id=#{userId}")
List<String> selectPermissionCodes(@Param("userId") Long userId);
```

依赖注入时新增的 Mapper 直接注入到相关 Service/基类即可（现有项目用构造器注入）。

---

## 三、权限加载链路改造（改 2 个文件）

**`security/model/SecurityUser.java`**：新增 `List<String> roles`、`List<String> permissions`；新增构造 `SecurityUser(User, List roles, List perms)`（保留原单参构造）。重写：

```java
getAuthorities() {
    return Stream.concat(
        roles.stream().map(c -> new SimpleGrantedAuthority("ROLE_" + c)),  // 角色统一加 ROLE_ 前缀
        permissions.stream().map(SimpleGrantedAuthority::new))             // 权限码原样
        .collect(Collectors.toList());
}
```

**`security/service/SecurityUserDetailsService.java`**：查到 `User` 后追加 `userMapper.selectRoleCodes(id)`、`selectPermissionCodes(id)`，返回 `new SecurityUser(user, roles, permissions)`。

**`security/filter/JwtAuthenticationFilter.java`**：**不改**（天然复用现有每请求加载逻辑）。

### 权限码常量（可维护性）
新建 `common/constant/PermissionConstants.java`，`public static final String` 常量（满足 SPEL 编译期常量，可字符串拼接）：`USER_PAGE="user:page"`、`USER_GET`、`USER_CREATE`、`USER_UPDATE`、`USER_DELETE`、`USER_ASSIGN_ROLE="user:assign-role"`、`ROLE_PAGE`、`ROLE_GET`、`ROLE_CREATE`、`ROLE_UPDATE`、`ROLE_DELETE`、`ROLE_ASSIGN_PERM="role:assign"`、`PERM_PAGE`、`PERM_GET`。用法：`@PreAuthorize("hasAuthority('" + PermissionConstants.USER_PAGE + "')")`。与 init.sql 中 code 一一对应。

### 全量权限码清单（14 条）
| code | name | 接口 |
|---|---|---|
| user:page | 用户分页 | GET /api/users |
| user:get | 用户详情 | GET /api/users/{id} |
| user:create | 新增用户 | POST /api/users |
| user:update | 修改用户 | PUT /api/users/{id} |
| user:delete | 删除用户 | DELETE /api/users/{id} |
| user:assign-role | 分配用户角色 | PUT /api/users/{id}/roles |
| role:page | 角色分页 | GET /api/roles |
| role:get | 角色详情 | GET /api/roles/{id} |
| role:create | 新增角色 | POST /api/roles |
| role:update | 修改角色 | PUT /api/roles/{id} |
| role:delete | 删除角色 | DELETE /api/roles/{id} |
| role:assign | 分配角色权限 | PUT /api/roles/{id}/permissions |
| perm:page | 权限分页 | GET /api/permissions |
| perm:get | 权限详情 | GET /api/permissions/{id} |

---

## 四、接口与 DTO/VO

**`UserController`（改）**：5 个既有方法加对应 `@PreAuthorize`（user:page/get/create/update/delete）；新增 `PUT /api/users/{id}/roles`，body `AssignRolesRequest{@NotEmpty List<Long> roleIds}`，`@PreAuthorize("hasAuthority('" + USER_ASSIGN_ROLE + "')")`。

**`UserService/Impl`（改）**：新增 `assignRoles(userId, roleIds)`，整表替换：校验用户存在 → `userRoleMapper.delete(userId)` → 非空则组装 `List<UserRole>` 并 `insertBatch`；同时校验目标 roleId 均存在。

**新建控制器**：
- `web/controller/admin/role/RoleController` `/api/roles`：page/get/create/update/delete/assignPermissions，各加 `@PreAuthorize`（role:page/get/create/update/delete/assign）。
- `web/controller/admin/perm/PermissionController` `/api/permissions`：page/get 端点（**权限只读，不做 CRUD**，新增能力走 init.sql，避免权限码漂移）。

**新建服务**：`service/RoleService/Impl`（page/get/create/update/delete/assignPermissions/listAll；delete 为逻辑删除且先物理清关联 `rolePermissionMapper.delete`+`userRoleMapper.delete`；查询/分页复用 `UserServiceImpl` 的 LambdaQueryWrapper+Page+`PageResult.of` 风格）、`service/PermissionService/Impl`（page/get/listAll）。

**DTO/VO**（遵循现有 web/dto、web/vo 风格）：
- Query：`RoleQuery`（code/name 模糊、status、分页）、`PermissionQuery`（code/name、分页）。
- Request：`RoleCreateRequest`（@NotBlank code/name, description, status）、`RoleUpdateRequest`、`AssignRolesRequest`、`AssignPermissionsRequest{@NotEmpty List<Long> permissionIds}`。
- VO：`RoleVO`（id,code,name,description,status,createTime,updateTime；详情可带 permissionIds）、`PermissionVO`（id,code,name,type,sort），均 `static from(...)`。

---

## 五、登录返回增强（建议做）

**`LoginVO`** 增加 `List<String> roles`、`List<String> permissions`：`AuthServiceImpl.login` 注入 `UserMapper` 调 `selectRoleCodes/selectPermissionCodes` 填入，供前端登录后按权限渲染。代价仅登录时多两次查询（一次性，可接受）；每请求的权限仍走 filter 的 fresh 查询，不冲突。

---

## 六、版本控制（分支）

新开并切换到分支 `rbac`（从当前基础版分支切出），本分支仅包含 RBAC 相关改动。提交时按「建表/实体 → 权限加载链路 → 接口 → 测试」分组提交。

---

## 七、测试改造与新增（`src/test/java/com/qsx`）

> ⚠️ **必改**：给 `UserController` 加 `@PreAuthorize` 后，既有用例用「普通注册用户」访问 `/api/users` 会从 200 变 403，必须同步改测试基类，否则回归红。

- **`BaseIntegrationTest`**：新增 `adminToken()` 超管工具 —— 注册唯一邮箱用户 → `userMapper.selectOne` 取 id → `UserRoleMapper.insert` 绑定 code='ADMIN' 角色（init.sql 已种，常驻）→ `loginGetToken`。`cleanDatabase`/`tearDown` 在 `userMapper.delete(null)` 之外**追加 `userRoleMapper.delete(null)`**（清关联脏数据；角色/权限/role-permission 仅 admin 需常驻，**不清理**）。
- **既有用例**：`UserControllerTest.adminToken()` 复用 `BaseIntegrationTest.adminToken()`；`SecurityAccessTest.valid_token_users_ok` 改用 adminToken（或断言普通用户 403）；`BusinessFlowTest` 中访问 `/api/users` 的用例改用 adminToken。
- **新增 `RbacTest`**：
  - 超管访问 `/api/roles`、`/api/permissions` → 200。
  - 普通用户（无角色）访问 `/api/users`、`/api/roles` → 403。
  - 细粒度：建只绑 `user:page` 的角色 → 分配权限 → 绑定给用户 → 该用户 `GET /api/users` 200、`GET /api/roles` 403。
  - 即时生效：同一 token，先去权限后访问 → 403，验证每请求查库。
  - 幂等：重复 `assignRoles` 不产生重复关联。

**验证命令**（需先对运行的 MySQL Docker 导入新版 init.sql）：
```
mvn -q compile
mvn -q -Dtest=RbacTest test
mvn -q -Dtest=UserControllerTest,SecurityAccessTest,BusinessFlowTest,AuthControllerTest test
mvn -q test    # 全量
```

---

## 八、风险与权衡

1. **每请求查库性能**：仅两条小结果集（角色/权限），无 N+1，低 QPS 管理后台可接受；后续补 Redis（key=userId，在分配角色/权限/删角色时失效）即可，本分支不做。
2. **权限码双源头**：`PermissionConstants` 与 init.sql 的 code 需保持一致；建议加一条测试遍历 `sys_permission` 断言覆盖清单。
3. **超管靠「绑定全部权限」**：新增权限码后需在 init.sql 追加并给 ADMIN 补 `sys_role_permission` 行，否则超管也拿不到——该模式的天然维护点，需文档化。
4. **角色/权限码唯一索引 + 逻辑删除**：逻辑删除记录仍占唯一索引位。删后重建同名角色时参考 `UserServiceImpl.delete` 的后缀释放策略，或先做唯一性校验抛 `BusinessException`；推荐先做穷比校验，暂不引入后缀。
5. **401 vs 403**：匿名/无效 token 由 `RestAuthenticationEntryPoint` 返回 401；已登录缺权限由 `RestAccessDeniedHandler` 返回 403，均已配置，测试断言需区分。
6. **测试回归面**：`@PreAuthorize` 破坏既有 user 用例，须与加载链路改动同一批次落地基类 `adminToken()`。
7. **超管改密**：`admin@qsx.com/admin123` 登录后走 `POST /auth/change-password`，或直接 SQL 更新 hash。

---

## 关键文件清单

- `sql/init.sql`（新增 4 表 DDL + 种子数据 —— 地基）
- `security/service/SecurityUserDetailsService.java`、`security/model/SecurityUser.java`（核心加载链路）
- `mapper/UserMapper.java`（角色/权限 join @Select）
- `common/constant/PermissionConstants.java`（新增，权限码集中）
- 新增：`domain/entity/{Role,Permission,UserRole,RolePermission}`、`mapper/{Role,Permission,UserRole,RolePermission}Mapper`、`service/{Role,Permission}Service(+impl)`、`web/controller/admin/role/RoleController`、`web/controller/admin/perm/PermissionController`、相关 DTO/VO
- 修改：`web/controller/admin/user/UserController.java`、`service/UserService(+impl)`、`web/vo/LoginVO.java`、`service/impl/AuthServiceImpl.java`
- 测试：`BaseIntegrationTest.java`（adminToken）、新增 `RbacTest.java`、调整 `UserControllerTest/SecurityAccessTest/BusinessFlowTest`