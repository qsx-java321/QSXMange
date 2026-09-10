# QSXManager 后台管理系统 - 会话总结

> 总结时间：2026-09-10 ｜ 来源：当前会话
> 读者导航：本文件是 QSXManager 项目首份总结，记录从零搭建基础版 + 功能验证的完整过程。

## 〇、本次状态变化
- **新增**：Spring Boot 工程骨架（约 30 个 Java 类 + application.yml + pom.xml）、数据库表 `sys_user`（sql/init.sql）、4 组自动化集成测试 + 基类（src/test）。
- **删除**：手工测试脚本 `script/api-test.ps1`、测试计划文档 `.trae/documents/qsxmanager_functional_test_plan.md`（一次性验证资产）。
- **修改**：分页 `pages` 字段修复；JWT 过滤器非法/过期 token 处理修复。
- **完成度变化**：从 0 → 基础版核心闭环完成（认证中心 + 用户管理）。
- **遗留事项**：尚未接入 RBAC（角色/权限）；未初始化 git 仓库。

## 一、基础上下文
- **项目 / 主题**：QSXManager，单体单模块前后端分离后台管理系统 · 基础版
- **时间范围**：2026-09-10（单会话）
- **涉及范围（文件 / 模块）**：根包 `com.qsx`（common/config/domain/security/service/web）、MySQL `QSXManager` 库、src/test 集成测试

## 二、已完成任务
- **搭建基础工程**（pom.xml 引入 Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Security + jjwt 0.12.6 + Validation + Lombok）
- **统一基础设施**（common/result 的 Result/PageResult/ResultCode、common/exception 的 BusinessException、全局异常处理）
- **数据库**：建 `sys_user` 表（字段含逻辑删除 deleted、email 唯一索引）
- **认证中心**（security 包）：
  - `/auth/register` 邮箱+密码注册（BCrypt、唯一校验）
  - `/auth/login` 登录返回 JWT（HS384）
  - `/auth/me` 当前用户、`/auth/change-password` 修改密码
  - SecurityConfig、JwtAuthenticationFilter、401/403 JSON 处理、SecurityUserDetailsService
- **用户管理**（`/api/users`）：分页查询（email/nickname/status 筛选）、增、改、详情、删
- **关键特性「逻辑删除释放邮箱」**：删除前将 email 拼 `#deleted_时间戳` 后缀再置 deleted=1，释放原邮箱供重新注册（UserServiceImpl.delete）
- **功能验证**：
  - 自动化集成测试 35 例全通过（Auth/User/BusinessFlow/SecurityAccess），复用真实 MySQL
  - 手工 PowerShell 端到端 16/16 通过，数据库层验证邮箱释放正确

## 三、错误与教训
- **做错了什么**：
  - 技术坑：MyBatis-Plus 3.5.9+ 分页 `PaginationInnerInterceptor` 需单独引入 `mybatis-plus-jsqlparser` 依赖，否则编译失败
  - 技术坑（真实缺陷）：JwtAuthenticationFilter 原实现直接调 `parseEmail`，解析非法/过期 token 时抛 MalformedJwt/ExpiredJwt 未捕获 → 返回 500 而非 401
  - 技术坑：逻辑删除后用 MyBatis-Plus `selectById` 验证已删记录会因自动 `deleted=0` 过滤查询不到（返回 null）
  - 流程失误：`UserControllerTest`/`BusinessFlowTest` 用固定邮箱断言，测试间逻辑删除遗留记录占住唯一索引导致后续执行冲突；Junit 断言失败源于数据隔离而非业务
- **吸取的教训**：
  - MyBatis-Plus 分页插件模块要在配置后及时编译验证
  - Security JWT 过滤器解析 token 必须 try-catch 静默降级为未认证，交 401 处理器，不能抛异常变 500
  - 验证逻辑删除的物理数据要用 JdbcTemplate/原生 SQL 绕过框架逻辑删除过滤
  - 集成测试跨用例复用真实库时用唯一邮箱（UUID），勿用固定值

## 四、关键技术抉择
- **决策与理由**：
  - 认证选纯 JWT 无状态、暂不接 Redis：最简，符合"最基础"定位；登出靠客户端清除 token
  - 注册/登录账号标识用邮箱而非用户名：用户明确指定
  - 逻辑删除释放邮箱用"email 拼 #deleted_时间戳"方案：避免与唯一索引冲突，同时保证原邮箱可重新注册
  - 测试复用现有 QSXManager 库而非 H2：保证与生产 SQL 一致；靠唯一邮箱 + 前后清空隔离
  - 测试方式自动化(JUnit+MockMvc)+手工(PowerShell)结合，并保留自动化测试类、删除一次性手工资产