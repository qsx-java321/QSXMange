# QSXManager · 操作日志模块 - 会话总结

> 总结时间：2026-09-11 ｜ 来源：当前会话（操作日志模块完整闭环）

## 〇、本次状态变化
- **新增 / 修改 / 删除**：
  - 新增：操作日志模块全量代码（`sys_operation_log` 表、`OperationLog` 实体/Mapper、`OperationLogService(+Impl)`、`OperationLogAspect`、`AsyncConfig`、`LogController`/`LogQuery`/`OperationLogVO`、`LogTest`）及两处安全 handler 改造
  - 修改：`init.sql`（日志表 + `system-log` 菜单 + `log:*` 权限）、`PermissionConstants`、`pom.xml`（补 `spring-boot-starter-aop`）、`RestAuthenticationEntryPoint`(401补记)、`RestAccessDeniedHandler`(403兜底补记)、`GlobalExceptionHandler`(400补记)
  - 报告：`测试报告-qsxmanager-log-20260911.md`（真实穿透测试）
- **完成度变化**：日志模块从方案 → 实现 → 真实测试(15场景) → 修复盲区 → 报告 → 提交，全链路完成。
- **遗留事项变化**：新增并已解决 C10 参数校验(400)记录盲区；保留未登录(401) `cost_ms=0` 的已知现状。

## 一、基础上下文
- **项目 / 主题**：QSXManager 后台管理系统 · 操作日志（AOP 访问审计）模块
- **时间范围**：2026-09-11
- **涉及范围（文件 / 模块 / 分支）**：`sql/init.sql`、`pom.xml`、根包 `com.qsx` 下 `aspect/config/mapper/service/web/security` 相关文件；分支 `master`。提交：`d6d7353`(feat) `a740e15`(test) `0762d1f`(docs)

## 二、已完成任务
- **方案设计**：AOP 包扫描全量审计 + 业务语义判定（`Result.code==200`）+ 401/403/400 补记 + `@Async` 异步落库 + `log.info` 打印
- **实现**（提交 `d6d7353`）：不可变日志表、切面、异步线程池(`CallerRunsPolicy`)、日志管理接口(`GET /api/logs` / 删除单条 / 清空)
- **集成测试**（提交 `a740e15`）：`LogTest` 6 例（成功/业务失败/401/403/分页/删清）
- **真实穿透测试**（报告 `测试报告-qsxmanager-log-20260911.md`）：15 场景逐条「请求→返回→落库→log.info」对照
- **修复盲区**：C10 参数校验(400)原本不记录 → `GlobalExceptionHandler` 补记，复测通过
- **文档**（提交 `0762d1f`）：README 更新 + 测试报告
- **提交推送**：3 commit 推送 `origin/master` 成功（`1c7a329..0762d1f`）

## 三、错误与教训
- **做错了什么**：
  - 技术坑：
    - 缺少 `spring-boot-starter-aop` 依赖 → Aspect 注解编译失败
    - `@Slf4j` 的静态 `log` 被参数名 `log` 遮蔽（`OperationLogServiceImpl`/`Aspect`），导致 `log.error/info` 编译或误用
    - `@Async` 切面记录 403：默认切面在内层捕获不到 `@PreAuthorize`(403)，需给切面加 `@Order(HIGHEST_PRECEDENCE)` 外层拦截
    - 测试竞态：异步落库用 `awaitAtLeast(计数)` 会被其他用例迟到日志污染 → 改为 `awaitAnyMatch(条件)`
  - 流程失误：
    - 真实测试前未察觉超管 `admin@qsx.com` 已被自动化测试逻辑删除（`deleted=1`）→ 必须先重置库
    - PowerShell 终端中文显示为乱码（控制台 code page），非数据问题，需 `--default-character-set=utf8mb4` 查询
- **吸取的教训**：
  - 触发 `@PreAuthorize` 异常的行为控制在切面最外层（`@Order`），否则方法安全 advice 抢先拦截
  - 异步落库的测试断言按「等待满足条件的日志」而非「等条数」，规避跨用例时序污染
  - 真实黑盒测试前先确认预置超管可用（未被污染），否则登录环节直接失败

## 四、关键技术抉择
- **决策与理由**：
  - AOP 包扫描全量（而非自定义注解）：访问审计覆盖所有 Controller，看 `Result.code` 判定业务成功/失败更准（业务失败常为 HTTP 200）
  - 400 校验失败由 `GlobalExceptionHandler.handleValidException` 补记：`@Valid` 在 AOP 切入点之前(参数解析层)抛异常，切面 `proceed()` 不执行，必须下沉补记；同步移除切面不可达分支避免双记
  - `@Async` 用自定义线程池（非默认 `SimpleAsyncTaskExecutor`）+ `CallerRunsPolicy` 兜底，避免无界线程与日志丢失
  - 日志表不可变（无 `deleted`/`update_time`）：审计数据只增不改，语义清晰
  - `/api/logs` 自身请求被切面排除：避免「日志的日志」无限膨胀
  - 未登录(401)走 `RestAuthenticationEntryPoint` 补记（不进 Controller，AOP 覆盖不到）