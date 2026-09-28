# 07 · 邮件投递与 DEBUG 验证码通道

> 被测代码 `f772da2` · 生成 2026-09-28 · 本轮补齐两条此前未覆盖的链路：**真实 SMTP 投递到本地 Mailpit**、**`debug=true` 直返码通道**。前提：Mailpit 运行于 SMTP `localhost:1025` / API `127.0.0.1:8025`。设计见 `04-邮箱验证码.md` §4。

## 范围

| 能力 | 说明 |
| :--- | :--- |
| `SmtpEmailService`（默认 `debug=false`） | `@Async("captchaMailExecutor")` + `JavaMailSender` 投递到 `spring.mail.*` |
| `DebugEmailService`（`debug=true`） | 不投递，验证码随 `POST /auth/captcha` 响应 `data.code` 直返 |
| 条件装配互斥 | 两个实现必须**互斥**，都装配会启动即 `NoUniqueBeanDefinitionException` |

## Track A 结果（`CaptchaMailSmokeTest` 1 例，全绿，未被跳过）

| 关注点 | 断言 |
| :--- | :--- |
| 发码 → Redis 已写码 | `qsx:auth:cap:REGISTER:{email}` 非空 |
| `@Async` 投递 → 真实 SMTP → Mailpit 收信 | 轮询 `/api/v1/messages` 命中收件人，正文 `contains(code)` 且含「验证码」 |

> Mailpit 在跑 → `assumeTrue` 未跳过，**端到端投递链路确实执行**。

## Track B 逐接口明细

### Run A（默认 `debug=false`，SMTP → Mailpit）

| # | 动作 | 结果 |
| --: | :--- | :--- |
| 1 | `POST /auth/captcha`（REGISTER，唯一邮箱） | 200 code 200（响应 `data` 为 `null`，符合非调试模式） |
| 2 | 直查 Redis 码键 | `qsx:auth:cap:REGISTER:{email}` = 6 位码 |
| 3 | 轮询 Mailpit → 取该收件人邮件 → 详情正文 | **已送达**，正文 `contains(码)` 且含「验证码」 |

> 与 Track A `CaptchaMailSmokeTest` 一致，证明「接口返回成功 ≠ 已验证送达」——本轮以 Mailpit 收信为准，**真实投递 + 异步线程池行为**端到端通过。

### Run B（`--qsx.captcha.debug=true` 重启，DebugEmailService）

| # | 动作 | 结果 |
| --: | :--- | :--- |
| 4 | 应用启动 | 打印 `DebugEmailService` WARN 横幅（调试模式启用）；**无 `NoUniqueBean`/`BeanCreationException`** → 互斥装配成立 |
| 5 | `POST /auth/captcha`（REGISTER） | 200 code 200，**`data.code` 直返非空** |
| 6 | 直返码 == Redis 码 | 一致（`552080` == `552080`） |
| 7 | 用直返码 `POST /auth/register` | 200 code 200（直返码可真正用于注册） |
| 8 | 查 Mailpit | 该邮箱 **0 封**邮件（DebugEmailService 生效，SMTP 未被使用） |
| 9 | `POST /auth/captcha`（CHANGE_PASSWORD，**未登录**） | 200 body code **401**（调试模式仍遵守场景登录约束） |

## 机制层

- 两实现按 `@ConditionalOnProperty(qsx.captcha.debug)` 互斥：Run A 用 SMTP、Run B 用 Debug，各自唯一装配。
- `debug=true` 下仍不放开场景约束（CHANGE_PASSWORD 需登录），仅改变投递行为，不改鉴权语义。
- 投递走 `@Async("captchaMailExecutor")` 由外部 Bean 调用（`CaptchaServiceImpl` → `EmailService`），异步生效已由收信时序间接验证。

## 发现的问题 / 观察项

- 无新增缺陷。
- 已知边界（设计 04 §11 / §7）：`debug=true` 时任意调用者都能从响应拿码、校验形同虚设——设计仅限本地联调，生产应做「生产 profile 拒绝 debug 启动」。本轮 Run B 属刻意开启验证该通道，非产品缺陷。

## 结论

两条此前未验证的链路（真实 SMTP→Mailpit 投递、debug 直返码）在双轨下全部按预期工作；两个投递实现条件装配互斥、debug 模式不破坏业务鉴权语义。