# QSXManager 后台管理系统

> 单体单模块、前后端分离的中小型后台管理系统（基础版）

当前为「基础版」，已实现认证中心（邮箱+密码+JWT）与用户管理（增删改查）的核心闭环。RBAC 角色/权限管理、Redis 缓存等将在后续阶段接入。

---

## 一、技术栈

| 分类 | 技术 |
|------|------|
| 语言 | Java 21 |
| 框架 | Spring Boot 3.5.16 |
| 持久层 | MyBatis-Plus 3.5.17（含分页插件） |
| 安全框架 | Spring Security + JWT（jjwt 0.12.6，HS384） |
| 数据库 | MySQL 8.x |
| 密码加密 | BCrypt |
| 接口风格 | RESTful，统一 `Result` / `PageResult` 返回 |

> 说明：基础版采用**纯 JWT 无状态认证**，暂未引入 Redis（登出依靠客户端清除 Token）。

## 二、项目结构

按分层分包，单体单模块，根包 `com.qsx`：

```
QSXManager
├── pom.xml                          # Maven 依赖与构建配置
├── sql/
│   └── init.sql                     # 建表脚本（sys_user）
├── script/                          # （如存在）辅助脚本
├── src/
│   ├── main/
│   │   ├── java/com/qsx/
│   │   │   ├── QsxProjectApplication.java   # Spring Boot 启动类
│   │   │   ├── common/                      # 通用基础能力
│   │   │   │   ├── result/                  # Result / PageResult / ResultCode
│   │   │   │   ├── exception/               # BusinessException
│   │   │   │   └── constant/                # SecurityConstants
│   │   │   ├── config/                      # 全局配置
│   │   │   │   ├── MybatisPlusConfig.java   # 分页插件 + 字段自动填充
│   │   │   │   └── properties/JwtProperties.java  # JWT 配置属性
│   │   │   ├── security/                    # Spring Security + JWT 认证授权
│   │   │   │   ├── config/SecurityConfig.java
│   │   │   │   ├── filter/JwtAuthenticationFilter.java
│   │   │   │   ├── handler/                 # 401 / 403 JSON 处理
│   │   │   │   ├── model/SecurityUser.java
│   │   │   │   ├── token/JwtTokenProvider.java
│   │   │   │   ├── service/SecurityUserDetailsService.java
│   │   │   │   └── util/SecurityUtils.java
│   │   │   ├── domain/                      # 领域模型
│   │   │   │   ├── entity/User.java
│   │   │   │   └── base/BaseEntity.java     # 通用字段（含逻辑删除）
│   │   │   ├── mapper/UserMapper.java       # MyBatis-Plus Mapper
│   │   │   ├── service/                     # 业务接口 + impl
│   │   │   │   ├── AuthService.java
│   │   │   │   └── UserService.java
│   │   │   └── web/                         # Web 接入层
│   │   │       ├── advice/GlobalExceptionHandler.java
│   │   │       ├── controller/
│   │   │       │   ├── auth/AuthController.java
│   │   │       │   └── admin/user/UserController.java
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
- 核心表：`sys_user`

`sys_user` 结构要点：
- `email` 唯一索引，作为登录账号
- `password` 存 BCrypt 加密结果
- `status`：0-正常、1-禁用
- `deleted`：逻辑删除标记（0-正常、1-已删除）

> 建表脚本见 `sql/init.sql`。

### 关键特性：逻辑删除释放邮箱
用户被逻辑删除时，系统先把原 `email` 拼上 `#deleted_<时间戳>` 后缀，再置 `deleted=1`。这样：
- 原邮箱从唯一索引中腾出，**同一邮箱可正常重新注册使用**；
- 已删除用户的记录仍保留在库中可追溯。

## 四、业务功能

### 认证中心（邮箱 + 密码 + JWT）

| 功能 | 接口 | 说明 |
|------|------|------|
| 注册 | `POST /auth/register` | 邮箱+密码注册，默认启用 |
| 登录 | `POST /auth/login` | 校验通过返回 JWT 与用户信息 |
| 当前用户 | `GET /auth/me` | 返回当前登录用户信息 |
| 修改密码 | `POST /auth/change-password` | 需校验原密码 |

### 用户管理（需登录）

| 功能 | 接口 | 说明 |
|------|------|------|
| 分页查询 | `GET /api/users` | 支持 email / nickname / status 筛选 |
| 新增用户 | `POST /api/users` | 需唯一邮箱 |
| 修改用户 | `PUT /api/users/{id}` | 支持改名、改状态、改邮箱（唯一性校验） |
| 用户详情 | `GET /api/users/{id}` | |
| 删除用户 | `DELETE /api/users/{id}` | 逻辑删除并释放邮箱 |

### 鉴权说明
- `/auth/register`、`/auth/login` 匿名放行；
- 其余接口需携带请求头 `Authorization: Bearer <token>`；
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

## 六、快速开始

### 环境要求
- JDK 21
- Maven 3.9+
- MySQL 8.x

### 1. 准备数据库
创建数据库并执行建表脚本：

```sql
CREATE DATABASE QSXManager CHARACTER SET utf8mb4;
USE QSXManager;
SOURCE sql/init.sql;
```

### 2. 配置数据源
编辑 `src/main/resources/application.yml`，设置 `spring.datasource` 与 `jwt.secret`。

### 3. 启动

```bash
mvn spring-boot:run
```

应用默认端口 `8080`。

### 4. 运行自动化测试（可选）

```bash
mvn clean test
```

测试复用本地 MySQL 的 QSXManager 库，执行前需保证库可连接和 `sys_user` 表存在。

## 七、后续规划

- [ ] RBAC 角色 / 权限 / 菜单管理
- [ ] 接口级与方法级鉴权（`@PreAuthorize`）
- [ ] Redis 缓存（Token 黑名单、权限缓存）
- [ ] 邮箱验证码注册
- [ ] 登录日志、操作日志

---

更多设计与实现细节见 `docs/session-notes/` 下的会话总结。