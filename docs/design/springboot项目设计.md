```yaml

项目:

  名称: QSXProJect

  定位: 单体单模块、前后端分离的中小型后台管理系统

  目标: 提供邮箱密码注册登录、JWT认证、完整RBAC权限、用户/角色/权限管理

  适合:

    - 企业内部后台

    - SaaS管理端

    - 运营管理平台

    - CMS/CRM/ERP后台底座

  不适合:

    - 高并发C端

    - 微服务架构

    - 分布式事务

    - 超大型多模块系统



技术栈:

  语言: Java 21

  框架: Spring Boot 3.5.16

  持久层: MyBatis-Plus 3.5.17

  安全框架: Spring Security

  数据库: MySQL

  缓存: Redis

  认证方式: 邮箱 + 密码 + JWT

  密码加密: BCrypt

  接口风格: RESTful

  返回格式: 统一 Result / PageResult



项目结构:

  根包: com.qsx

  说明: 按层分包，单体单模块，适合当前阶段

  包:

    - 路径: QsxProjectApplication

      职责: Spring Boot 启动类

    - 路径: common

      职责: 通用基础能力，不依赖业务、Web、Security、MyBatis-Plus

      子包:

        - 路径: result

          职责: Result、PageResult、ResultCode

        - 路径: exception

          职责: BusinessException、基础异常定义

        - 路径: constant

          职责: 通用常量、SecurityConstants、CacheConstants

        - 路径: util

          职责: 纯通用工具类

    - 路径: config

      职责: 全局配置类

      包含:

        - MybatisPlusConfig

        - WebMvcConfig

        - JacksonConfig

        - properties

    - 路径: security

      职责: Spring Security 与 JWT 认证授权

      子包:

        - 路径: config

          职责: SecurityConfig

        - 路径: filter

          职责: JwtAuthenticationFilter

        - 路径: handler

          职责: 401、403 JSON 处理

        - 路径: token

          职责: Token 生成、解析、刷新、黑名单

        - 路径: model

          职责: LoginUser、SecurityUser

        - 路径: util

          职责: SecurityUtils

        - 路径: service

          职责: UserDetailsService 实现，如 SecurityUserDetailsService

    - 路径: domain

      职责: 领域模型

      子包:

        - 路径: entity

          职责: 数据库实体，如 User、Role、Permission、UserRole、RolePermission

        - 路径: enums

          职责: 业务枚举，如 UserStatus、RoleStatus、PermissionType

        - 路径: base

          职责: BaseEntity 等实体基类

    - 路径: mapper

      职责: MyBatis-Plus Mapper 接口

    - 路径: service

      职责: 业务服务接口

      核心接口:

        - AuthService

        - UserService

        - RoleService

        - PermissionService

        - EmailService

        - CaptchaService

      子包:

        - 路径: impl

          职责: 服务实现

    - 路径: web

      职责: Web 接入层

      子包:

        - 路径: advice

          职责: 全局异常处理 GlobalExceptionHandler

        - 路径: controller

          职责: 控制器

          子包:

            - 路径: admin

              子包:

                - user

                - role

                - permission

            - 路径: auth

        - 路径: dto

          子包:

            - request

            - query

        - 路径: vo

          职责: 返回前端的视图对象

    - 路径: converter

      职责: Entity、DTO、VO 转换器，可用 MapStruct

    - 路径: infrastructure

      职责: 基础设施

      子包:

        - 路径: redis

          职责: Redis 配置与缓存服务

  资源目录:

    - 路径: resources/mapper

      职责: 复杂 SQL 的 MyBatis XML

    - 路径: resources/application.yml

      职责: 应用配置



依赖方向:

  规则:

    - web -> service -> mapper -> domain

    - security -> mapper/domain

    - config -> 各配置类

    - common -> 尽量只依赖 Java 标准库和通用工具

  禁止:

    - service 依赖 web

    - domain 依赖 web/dto/vo

    - common 依赖 Security/MyBatis-Plus/Web

    - security 与 service 循环依赖



核心业务功能:

  最小可用版本:

    名称: 认证中心

      功能:

        - 邮箱验证码发送与校验

        - 邮箱 + 密码注册

        - 邮箱 + 密码登录

        - JWT 签发与校验

        - 获取当前登录用户信息

        - 登出

        - 修改密码

      相关包:

        - security

        - service.AuthService

        - service.EmailService

        - service.CaptchaService

        - infrastructure.redis

    - 名称: 用户管理

      功能:

        - 用户分页查询

        - 用户新增、修改、删除

        - 启用、禁用

        - 逻辑删除

        - 分配角色

        - 重置密码

      相关包:

        - web.controller.admin.user

        - service.UserService

        - mapper.UserMapper

        - domain.entity.User

    - 名称: 角色管理

      功能:

        - 角色增删改查

        - 角色状态管理

        - 给角色分配权限

        - 给用户分配角色

      相关包:

        - web.controller.admin.role

        - service.RoleService

        - mapper.RoleMapper

        - domain.entity.Role

    - 名称: 权限与菜单管理

      功能:

        - 目录、菜单、按钮统一管理

        - 权限树查询

        - 权限标识维护

        - 动态菜单

        - 按钮级权限

      相关包:

        - web.controller.admin.permission

        - service.PermissionService

        - mapper.PermissionMapper

        - domain.entity.Permission

    - 名称: RBAC 鉴权

      功能:

        - 用户绑定角色

        - 角色绑定权限

        - 接口级鉴权

        - 方法级鉴权

        - 前端菜单和按钮控制

      权限标识示例:

        - "system:user:list"

        - "system:user:add"

        - "system:user:update"

        - "system:user:delete"

        - "system:role:assign"

    - 名称: 基础设施

      功能:

        - 统一返回 Result

        - 分页返回 PageResult

        - 全局异常处理

        - 参数校验

        - Redis 缓存

        - 登录日志、操作日志可作为后续扩展

  后续可扩展:

    - 字典管理

    - 参数配置

    - 公告通知

    - 文件上传下载

    - 登录日志

    - 操作日志

    - 在线用户

    - 数据权限

    - 定时任务

    - 代码生成

    - 多租户

    - 工作流

    - 具体业务模块



核心闭环:

  - 发送邮箱验证码

  - 邮箱注册

  - 邮箱密码登录

  - 返回 JWT

  - 获取当前用户信息

  - 用户 CRUD

  - 角色 CRUD

  - 权限菜单树

  - 用户分配角色

  - 角色分配权限

  - 接口鉴权

  - 登出



RBAC模型:

  核心实体:

    - User: 用户

    - Role: 角色

    - Permission: 权限或菜单

    - UserRole: 用户角色关联

    - RolePermission: 角色权限关联

  关系:

    - 用户 多对多 角色

    - 角色 多对多 权限

    - 权限 可形成树形结构

  鉴权方式:

    - Spring Security 管理认证与授权

    - JWT（access token，30 分钟短期）保存登录状态

    - refresh token（32 字节随机串，仅存 SHA-256 哈希于 Redis）承载会话，key=qsx:auth:refresh:{userId}，单端登录、滑动续期 7 天 + 30 天绝对上限

    - Redis 保存权限缓存（qsx:auth:perm:{userId}）、刷新会话；会话链路 Redis 异常 fail-closed（拒绝续期），权限缓存降级回源 MySQL

    - 方法上使用 PreAuthorize 校验权限标识



认证流程:

  注册:

    - 用户提交邮箱、密码

    - 校验邮箱是否已注册

    - BCrypt 加密密码

    - 保存用户，默认启用

  登录:

    - 用户提交邮箱和密码

    - Spring Security 进行认证（实时查库，禁用账号 1003）

    - 认证成功后生成 access token（JWT，30 分钟）+ refresh token（写 Redis，覆盖旧会话实现单端登录）

    - 返回 token、refreshToken、用户信息、角色、权限

  请求鉴权:

    - JwtAuthenticationFilter 解析 token，实时查库加载用户并校验 status（禁用即 401）

    - 校验 token 签名与过期

    - 加载用户权限（Redis 缓存优先）并写入 SecurityContext

    - Spring Security 根据权限决定是否放行

  刷新令牌（access 过期后）:

    - 客户端传 {userId, refreshToken} 调 POST /auth/refresh

    - 实时查库校验用户存在且未禁用（安全兜底）

    - 校验 Redis 中 hash 匹配 + 未超 30 天绝对上限，通过后轮换：删旧 refresh、发新 refresh + 新 access

    - 旧 refresh 一经使用立即失效（重放即 1019），Redis 异常 fail-closed 返回 1019

  登出:

    - POST /auth/logout 删除当前用户 refresh 会话（Redis 异常降级成功）

  管理员强制登出（踢下线）:

    - 持 user:kick 权限调 POST /api/users/{id}/kick

    - 禁止踢自己（1022）、禁止踢内置超管（1021）

    - 删除目标用户 refresh 会话；其当前 access 最长再用 30 分钟，需重新登录

  禁用/解冻:

    - PUT /api/users/{id} 传 status 0/1（沿用 update 链路）；禁用自己的旧 access 即时 401、refresh 拒绝续期

    - 保护：禁止禁用自己（1022）、禁止禁内置超管（1020）；删除用户时联动清理 refresh 会话

  异常:

    - 未认证返回 401 JSON

    - 无权限返回 403 JSON



数据表建议:

  核心表:

    - sys_user

    - sys_role

    - sys_permission

    - sys_user_role

    - sys_role_permission

  可选表:

    - sys_login_log

    - sys_operation_log

    - sys_dict_type

    - sys_dict_data

    - sys_config

    - sys_notice

    - sys_file

  通用字段:

    - id

    - create_time

    - update_time

    - create_by

    - update_by

    - deleted

    - version



开发顺序建议:

  - 1: 初始化 Spring Boot 3.5.16 + JDK 21 + MyBatis-Plus 3.5.17 + Spring Security

  - 2: 建立 common、config、domain、mapper、service、web 基础结构

  - 3: 完成统一返回、全局异常、分页、BaseEntity、自动填充

  - 4: 建 RBAC 五张核心表与实体、Mapper

  - 5: 完成邮箱验证码、注册、登录、JWT、SecurityConfig

  - 6: 完成当前用户信息、登出、修改密码

  - 7: 完成用户管理 CRUD 与分配角色

  - 8: 完成角色管理 CRUD 与分配权限

  - 9: 完成权限/菜单树与动态菜单接口

  - 10: 接入 Redis 缓存、登录日志、操作日志

  - 11: 前端联调，动态路由、按钮权限



注意事项:

  - common 保持干净，不要变成垃圾桶

  - Entity 不直接返回前端，避免泄露 password

  - Controller 不写业务逻辑，不直接操作 QueryWrapper

  - Service 按业务划分，不机械按表划分

  - security.service 不要依赖 AuthService，避免循环依赖

  - JWT 登出、踢人、权限变更要配合 Redis

  - 邮箱验证码要限流、过期、防刷

  - 逻辑删除与邮箱唯一索引要处理冲突

  - 权限变更后注意缓存一致性

  - 单体单模块先跑通核心闭环，不要过度设计



最终成品:

  描述: 一个可用的中小型前后端分离后台管理系统

  管理员可操作:

    - 登录后台

    - 查看动态菜单

    - 管理用户

    - 管理角色

    - 管理权限和菜单

    - 给用户分配角色

    - 给角色分配权限

    - 控制按钮和接口权限

    - 查看当前登录信息

    - 修改密码

    - 后续查看日志、字典、配置、公告等

  一句话总结: 这套结构可以做出标准 RBAC 后台管理平台，核心是邮箱认证、JWT、用户/角色/权限管理和统一基础设施。

```