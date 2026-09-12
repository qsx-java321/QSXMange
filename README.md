# QSXManager 后台管理系统

> 单体单模块、前后端分离的中小型后台管理系统

当前已实现「认证中心（邮箱+密码+JWT）」「RBAC 权限管理（角色/权限）」「菜单管理（动态路由菜单树）」「操作日志（AOP 访问审计）」「用户 Excel 批量导入导出」与「RBAC 权限缓存（Redis）」，形成 认证 → 授权 → 业务 → 前端路由 的完整闭环。邮箱验证码、Token 黑名单等将在后续阶段接入。

---

## 一、技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.5.16 |
| 持久层 | MyBatis-Plus 3.5.17（含分页插件、逻辑删除） |
| 安全框架 | Spring Security + JWT（jjwt 0.12.6，HS384）+ `@PreAuthorize` 方法级鉴权 |
| 权限缓存 | Redis 7.4.x（`spring-boot-starter-data-redis`，用户权限码缓存，未命中回源 MySQL 并回填） |
| 切面 | Spring AOP（`spring-boot-starter-aop`）操作日志切面 + `@Async` 异步落库 |
| Excel | EasyExcel 4.0.3（SAX 流式读写，导入解析上传流 / 导出直出响应流，不落盘） |
| 数据库 | MySQL 8.x |
| 密码加密 | BCrypt |
| 接口风格 | RESTful，统一 `Result` / `PageResult` 返回 |

> 说明：采用**纯 JWT 无状态认证**。角色/权限码经 **Redis 缓存**（key=`qsx:auth:perm:{userId}`，仅缓存权限码，用户行/密码/状态仍实时查库），未命中回源 MySQL 并回填；权限/角色/用户变更在**事务提交后失效**相关缓存（`@TransactionalEventListener` AFTER_COMMIT），变更即时生效。登出依靠客户端清除 Token，Token 黑名单将在后续阶段接入。Redis 异常自动降级为实时查库，可用 `qsx.rbac-cache.enabled=false` 一键关闭缓存。

## 二、项目结构

按分层分包，单体单模块，根包 `com.qsx`：

```
QSXManager
├── pom.xml                          # Maven 依赖与构建配置
├── sql/
│   └── init.sql                     # 建表脚本（sys_user + RBAC 四表 + 菜单/权限 + 预置数据）
├── src/
│   ├── main/
│   │   ├── java/com/qsx/
│   │   │   ├── QsxProjectApplication.java   # Spring Boot 启动类
│   │   │   ├── common/                      # 通用基础能力
│   │   │   │   ├── result/                  # Result / PageResult / ResultCode
│   │   │   │   ├── exception/               # BusinessException
│   │   │   │   └── constant/                # SecurityConstants / PermissionConstants
│   │   │   ├── config/                      # 全局配置
	    │   │   │   │   ├── MybatisPlusConfig.java   # 分页插件 + 字段自动填充
	    │   │   │   │   ├── AsyncConfig.java         # 异步线程池（操作日志落库）
	    │   │   │   │   ├── event/                   # 权限缓存失效事件 + AFTER_COMMIT 监听器
	    │   │   │   │   └── properties/              # JwtProperties / RbacCacheProperties
    │   │   │   ├── aspect/                      # AOP 切面
    │   │   │   │   └── OperationLogAspect.java  # 操作日志：Controller 全量审计 + @Async 落库
│   │   │   ├── security/                    # Spring Security + JWT 认证授权
│   │   │   │   ├── config/SecurityConfig.java
│   │   │   │   ├── filter/JwtAuthenticationFilter.java
│   │   │   │   ├── handler/                 # 401 / 403 JSON 处理
│   │   │   │   ├── model/SecurityUser.java  # 封装角色码(ROLE_前缀) + 权限码
│   │   │   │   ├── token/JwtTokenProvider.java
│   │   │   │   ├── service/SecurityUserDetailsService.java
│   │   │   │   └── util/SecurityUtils.java
│   │   │   ├── domain/                      # 领域模型
│   │   │   │   ├── entity/                  # User / Role / Permission / UserRole / RolePermission
│   │   │   │   └── base/BaseEntity.java     # 通用字段（含逻辑删除）
│   │   │   ├── mapper/                      # MyBatis-Plus Mapper（含角色/权限联表查询）
│   │   │   ├── service/                     # 业务接口 + impl
	│   │   │   │   ├── AuthService.java         # 认证中心
	│   │   │   │   ├── UserService.java         # 用户管理（含分配角色）
	│   │   │   │   ├── RoleService.java         # 角色管理（含分配权限）
	│   │   │   │   ├── PermissionService.java   # 菜单+权限管理（含树形）
	│   │   │   │   └── PermissionCacheService.java  # RBAC 权限缓存（Redis 优先 + MySQL 回源）
│   │   │   └── web/                         # Web 接入层
│   │   │       ├── advice/GlobalExceptionHandler.java
│   │   │       ├── controller/
│   │   │       │   ├── auth/AuthController.java
│   │   │       │   ├── admin/{user,role,perm,menu,log}/  # 用户/角色/权限/菜单/日志管理控制器
│   │   │       ├── dto/{request,query}/
│   │   │       └── vo/                      # 视图对象（不暴露密码）
│   │   └── resources/
│   │       └── application.yml              # 应用配置（数据源 / JWT / MyBatis-Plus）
│   └── test/java/com/qsx/                   # 自动化集成测试（MockMvc + 真实 MySQL）
└── docs/
    └── session-notes/                       # 会话总结存档
```

**依赖方向**：`web → service → mapper → domain`，`security` 依赖 `mapper/domain`，`common` 保持纯净、不依赖业务。

## 三、数据库

- 数据库名：`QSXManager`
- 共 6 张表（见 `sql/init.sql`）

| 表 | 说明 |
|------|------|
| `sys_user` | 用户表，`email` 唯一索引，`status`(0-正常/1-禁用)，`deleted` 逻辑删除 |
| `sys_role` | 角色表，`code` 唯一索引，如 `ADMIN`，`deleted` 逻辑删除 |
| `sys_permission` | 菜单+权限表，`code` 唯一索引；`type`(MENU-菜单/PERMISSION-按钮权限)，菜单经 `parent_id` 组成树 |
| `sys_user_role` | 用户-角色关联表，纯关系表、物理删除、整表替换语义 |
| `sys_role_permission` | 角色-权限关联表，纯关系表、物理删除、整表替换语义 |
| `sys_operation_log` | 操作日志表，审计数据不可变（无 deleted/update_time），异步落库 |

### 预置数据（幂等，`INSERT IGNORE`）
- 6 个菜单（`system` 系统管理 → 用户/角色/权限/菜单/日志管理）
- 22 个按钮权限码（`user:*` / `role:*` / `perm:*` / `menu:*` / `log:*`，与 `PermissionConstants` 一一对应，归属挂载到对应菜单下）
- `ADMIN` 超级管理员角色，绑定全部菜单与权限
- 超管账号 `admin@qsx.com / admin123`

### 关键特性
- **逻辑删除释放邮箱**：用户被逻辑删除时，系统先把原 `email` 拼上 `#deleted_<时间戳>` 后缀，再置 `deleted=1`。原邮箱从唯一索引中腾出，**同一邮箱可正常重新注册使用**，已删除记录仍可追溯。
- **整表替换授权**：分配角色/权限均先物理删旧关联、再批量插新。
- **Excel 文件不落盘**：导入以输入流流式解析（SAX），导出/模板直接写响应流，服务器不产生临时文件；导入采用**整批校验 + 整体拒绝**，任一数据行不合法则全部不落库并返回错误明细。

## 四、业务功能

### 认证中心（邮箱 + 密码 + JWT）

| 功能 | 接口 | 说明 |
|------|------|------|
| 注册 | `POST /auth/register` | 邮箱+密码注册，默认启用 |
| 登录 | `POST /auth/login` | 校验通过返回 JWT 与用户信息（含角色码、权限码） |
| 当前用户 | `GET /auth/me` | 返回当前登录用户信息 |
| 修改密码 | `POST /auth/change-password` | 需校验原密码 |

### 用户管理（需登录 + 权限）

| 功能 | 接口 | 权限码 | 说明 |
|------|------|------|------|
| 分页查询 | `GET /api/users` | `user:page` | 支持 email / nickname / status 筛选 |
| 用户详情 | `GET /api/users/{id}` | `user:get` | |
| 新增用户 | `POST /api/users` | `user:create` | 需唯一邮箱 |
| 修改用户 | `PUT /api/users/{id}` | `user:update` | 支持改名、改状态、改邮箱（唯一性校验） |
| 删除用户 | `DELETE /api/users/{id}` | `user:delete` | 逻辑删除并释放邮箱 |
| 分配角色 | `PUT /api/users/{id}/roles` | `user:assign-role` | 整表替换用户角色 |
| 下载导入模板 | `GET /api/users/import/template` | `user:import` | 表头：邮箱/昵称/状态/角色编码 |
| 批量导入 | `POST /api/users/import` | `user:import` | multipart 上传，整批校验整体拒绝，统一默认密码 |
| 批量导出 | `GET /api/users/export` | `user:export` | 按筛选条件导出全量，不含密码 |

### 角色管理（需登录 + 权限）

| 功能 | 接口 | 权限码 | 说明 |
|------|------|------|------|
| 分页查询 | `GET /api/roles` | `role:page` | 支持 code / name / status 筛选 |
| 角色列表 | `GET /api/roles/all` | `role:page` | 仅启用角色，用于下拉选择 |
| 角色详情 | `GET /api/roles/{id}` | `role:get` | 含已绑定权限 ID 列表 |
| 新增角色 | `POST /api/roles` | `role:create` | 编码唯一性校验 |
| 修改角色 | `PUT /api/roles/{id}` | `role:update` | 编码唯一性校验（排除自身） |
| 删除角色 | `DELETE /api/roles/{id}` | `role:delete` | 内置 `ADMIN` 禁止删除；先清关联再逻辑删除 |
| 分配权限 | `PUT /api/roles/{id}/permissions` | `role:assign` | 整表替换角色权限 |

### 权限管理（需登录 + 权限，只读）

| 功能 | 接口 | 权限码 | 说明 |
|------|------|------|------|
| 分页查询 | `GET /api/permissions` | `perm:page` | 支持 code / name 筛选 |
| 权限列表 | `GET /api/permissions/all` | `perm:page` | 按 sort 排序，用于分配权限 |
| 权限详情 | `GET /api/permissions/{id}` | `perm:get` | |

> 权限码维护走 `sql/init.sql`（增删权限后需同步 `PermissionConstants` 并为 ADMIN 补绑），分页查询为只读接口。

### 菜单管理（需登录 + 权限）

菜单与按钮权限共用 `sys_permission` 表：`type=MENU` 组成树（`parent_id` 自关联），`type=PERMISSION` 作为叶子挂在菜单下。

| 功能 | 接口 | 权限码 | 说明 |
|------|------|------|------|
| 菜单树 | `GET /api/menus/tree` | `menu:tree` | 全量菜单+按钮权限树（管理端） |
| 我的菜单 | `GET /api/menus/current` | 登录即可 | 当前用户菜单树（按权限过滤+补祖先，供前端动态路由） |
| 新增菜单 | `POST /api/menus` | `menu:create` | 支持菜单/按钮权限，标识唯一性校验 |
| 修改菜单 | `PUT /api/menus/{id}` | `menu:update` | 含防环校验（父节点不能指向自身或子孙） |
| 删除菜单 | `DELETE /api/menus/{id}` | `menu:delete` | 存在子节点禁止删除；删除时清理角色-权限关联 |

### 操作日志（需登录 + 权限）

操作日志通过 **Spring AOP 切面**全量拦截 `/api/*` 业务调用（`com.qsx.web.controller` 包扫描，`@Around` + `@Async` 异步落库），并补记 Security 层未登录(401)；越权(403) 由切面捕获。判定以业务语义（`Result.code==200`）为准，另通过 `log.info` 打印到日志文件。日志表为审计数据、不可变。

| 功能 | 接口 | 权限码 | 说明 |
|------|------|------|------|
| 分页查询 | `GET /api/logs` | `log:page` | 支持 username / url / success / method / 时间段筛选 |
| 删除日志 | `DELETE /api/logs/{id}` | `log:delete` | 物理删除单条 |
| 清空日志 | `DELETE /api/logs` | `log:delete` | 物理清空全部 |

### 鉴权说明
- `/auth/register`、`/auth/login` 匿名放行；
- 其余接口需携带请求头 `Authorization: Bearer <token>`；
- URL 级 `authenticated()` + 方法级 `@PreAuthorize` 权限码校验（双保险），权限码经 Redis 缓存加载（未命中回源 MySQL，权限变更事务提交后即时失效）；
- 未登录返回 401，无权限返回 403，参数校验失败返回 400，均统一为 JSON 格式。

## 五、统一返回格式

所有接口返回 `Result{code, message, data}`，分页接口的 `data` 为 `PageResult`。

```json
{ "code": 200, "message": "操作成功", "data": { } }
```

常用业务码：

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数校验失败 |
| 401 | 未登录 / 登录已过期 |
| 403 | 无操作权限 |
| 1001 | 该邮箱已被注册 |
| 1002 | 邮箱或密码错误 |
| 1003 | 账号已被禁用 |
| 1004 | 用户不存在 |
| 1006 | 原密码错误 |
| 1009 | 角色不存在 |
| 1010 | 角色编码已存在 |
| 1011 | 角色已被用户使用，无法删除 |
| 1012 | 权限不存在 |
| 1013 | 菜单不存在 |
| 1014 | 存在子菜单，无法删除 |
| 1015 | 父菜单无效 |
| 1016 | 菜单或权限标识已存在 |
| 1017 | 导入数据校验失败（含错误行明细） |
| 1018 | 导入数据量超过限制 |

## 六、快速开始

### 环境要求
- JDK 21
- Maven 3.9+
- MySQL 8.x

### 1. 准备数据库
创建数据库并执行建表脚本（含 RBAC 四表与预置数据）：

```sql
CREATE DATABASE QSXManager CHARACTER SET utf8mb4;
USE QSXManager;
SOURCE sql/init.sql;
```

### 2. 配置数据源
编辑 `src/main/resources/application.yml`，设置 `spring.datasource` 与 `jwt.secret`（正式环境务必替换 JWT 密钥）。

### 3. 启动

```bash
mvn spring-boot:run
```

应用默认端口 `8080`。

### 4. 登录体验
使用预置超管账号登录，即可调用全部管理接口：

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@qsx.com","password":"admin123"}'
```

### 5. 运行自动化测试（可选）

```bash
mvn clean test
```

测试复用本地 MySQL 的 QSXManager 库与本地 Redis，执行前需保证两者可连接（Redis 不可用时业务自动降级为实时查库，仅缓存专项测试会受影响）。

## 七、后续规划

- [x] RBAC 权限缓存（Redis，含失效与降级）
- [ ] Token 黑名单（登出失效）
- [ ] 邮箱验证码注册
- [ ] 登录日志（操作日志已实现）
- [ ] 前端管理界面（对接菜单树动态路由）

---

更多设计与实现细节见 `docs/session-notes/` 下的会话总结。
