# 07 · 邮件投递与 DEBUG 验证码通道

> **📍 定位**：本文是 `docs/test/分域基线/` 下的**自动化用例分域索引**——只记「接口 ↔ 测试类 ↔ 断言点」这类**稳定**信息。
> **例数**见 [`../项目测试报告.md` §四.1](../项目测试报告.md)（唯一数据源，本文不重复记数）；
> **接口的权威行为**以 [`docs/design/04-邮箱验证码.md`](../../design/04-邮箱验证码.md) §4 与**当前源码**为准。
> **已对齐至** `b5c922b`（2026-10-01 核对，Track A 全绿）。

## 覆盖矩阵（能力 ↔ 测试类）

| 能力 | 说明 | 测试类 |
| :--- | :--- | :--- |
| `SmtpEmailService`（默认 `debug=false`） | `@Async("captchaMailExecutor")` + `JavaMailSender` 投递到 `spring.mail.*` | CaptchaMailSmokeTest |
| `DebugEmailService`（`debug=true`） | 不投递，验证码随 `POST /auth/captcha` 响应 `data.code` 直返 | （真实 HTTP 专项，见下） |
| 投递链路的**不可回退决策** | SMTP 三超时 + 邮件池 `AbortPolicy` | MailChainConfigTest |
| 生产形态的**配置守卫** | 危险默认值两道闸；`debug=true` 在 prod 启动即拒 | ProdSecurityGuardTest |

> **两个实现条件装配必须互斥**（`@ConditionalOnProperty(qsx.captcha.debug)`），
> 都装配会启动即 `NoUniqueBeanDefinitionException`。

## 测试类与断言点

| 测试类 | 关键断言点 |
| :--- | :--- |
| `CaptchaMailSmokeTest` | 发码 → Redis 已写码 → `@Async` 投递 → **真实 SMTP → Mailpit 收信**：轮询 `/api/v1/messages` 命中收件人且正文 `contains(code)`（Mailpit 未启动时 `assumeTrue` 跳过） |
| `MailChainConfigTest` | **配置守卫**：`spring.mail.properties.mail.smtp` 的 `connectiontimeout` / `timeout` / `writetimeout` 必须显式配置（JavaMail 默认**无限等待**）；`captchaMailExecutor` 必须是 `AbortPolicy` 而非 CallerRuns |
| `ProdSecurityGuardTest` | **生产形态守卫**：`application-prod.yml` 用无默认值占位符（未提供即启动失败）；`ProdSecurityGuard` 拦「提供了值但是仓库公开值」；`qsx.captcha.debug=true` 在 prod 启动即拒 |

## 两个不可回退的决策

| 决策 | 原因 |
| :--- | :--- |
| **SMTP 三个超时必须显式配置** | JavaMail 默认值是「无限等待」——SMTP 服务商被 greylist / 限流 / 链路丢包时，投递线程会一直挂住不归还 |
| **`captchaMailExecutor` 用 `AbortPolicy`** | 用 CallerRuns 会让**请求线程**在队列打满时同步做 SMTP，外部依赖劣化会把「发信慢」放大成「全站不可用」；被拒由 `CaptchaServiceImpl` 兜住（码已落 Redis，接口照常成功，ERROR 留痕） |

> 这两条都有测试固定（`MailChainConfigTest`），**改回默认值即转红**。

## 生产形态的 debug 通道（已收口）

`debug=true` 时任意调用者都能从响应里直接拿到验证码，校验形同虚设。当前收口方式：

1. `application-prod.yml` 里相关配置用**无默认值的占位符**（未提供即启动失败）；
2. `ProdSecurityGuard` 拦「提供了值、但值是仓库公开值」；
3. `qsx.captcha.debug=true` 在 prod 下**启动即拒**。

## 业务语义（与投递解耦）

- 投递方式对业务透明：`CaptchaServiceImpl` 只调 `EmailService` 端口；`@Async("captchaMailExecutor")`
  靠**外部 Bean 调用**触发代理（**不得自调用**）。
- **接口返回成功 ≠ 已验证送达**：码在投递**之前**就已落 Redis，投递失败只记 ERROR。
- `debug=true` **不放开场景约束**（`CHANGE_PASSWORD` 仍需登录），仅改变投递行为，不改鉴权语义。

## ⏳ 历史快照 · Track B（`f772da2`，2026-09-28 当轮）

> 以下是那一轮的**真实 HTTP 记录，不代表当前**；当前结果见总报告 §七。

### Run A（默认 `debug=false`，SMTP → Mailpit）

| # | 动作 | 结果 |
| --: | :--- | :--- |
| 1 | `POST /auth/captcha`（REGISTER，唯一邮箱） | 200 code 200（响应 `data` 为 `null`，符合非调试模式） |
| 2 | 直查 Redis 码键 | `qsx:auth:cap:REGISTER:{email}` = 6 位码 |
| 3 | 轮询 Mailpit → 取该收件人邮件 → 详情正文 | **已送达**，正文 `contains(码)` 且含「验证码」 |

### Run B（`--qsx.captcha.debug=true` 重启，DebugEmailService）

| # | 动作 | 结果 |
| --: | :--- | :--- |
| 4 | 应用启动 | 打印 `DebugEmailService` WARN 横幅；**无 `NoUniqueBean` / `BeanCreationException`** → 互斥装配成立 |
| 5 | `POST /auth/captcha`（REGISTER） | 200 code 200，**`data.code` 直返非空** |
| 6 | 直返码 == Redis 码 | 一致 |
| 7 | 用直返码 `POST /auth/register` | 200 code 200（直返码可真正用于注册） |
| 8 | 查 Mailpit | 该邮箱 **0 封**邮件（DebugEmailService 生效，SMTP 未被使用） |
| 9 | `POST /auth/captcha`（CHANGE_PASSWORD，**未登录**） | 200 body code **401**（调试模式仍遵守场景登录约束） |

## 结论

真实 SMTP → Mailpit 投递与 `debug` 直返码两条链路在双轨下均按预期工作；
两个投递实现条件装配互斥、`debug` 模式不破坏业务鉴权语义；投递链路的超时与线程池策略已由配置守卫测试固定。
