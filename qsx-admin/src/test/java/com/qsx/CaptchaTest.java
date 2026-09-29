package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.config.properties.CaptchaProperties;
import com.qsx.service.CaptchaService;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱验证码测试：发送接口（HTTP）+ 校验机制（直连服务层）。
 *
 * <p>与 SessionLuaTest 同一定位：这里既有 HTTP 层的接口语义（场景前置条件、业务码），
 * 也有直连 {@link CaptchaService} 的机制层断言（用后即焚、错次作废、跨场景隔离）——
 * 后者不经 HTTP，因为在校验尚未接入注册/改密流程前，校验入口只在服务层。
 *
 * <p>断言纪律：验证「用后即焚」「错次作废」时除了看业务码，**必须断言 Redis 键的形态**
 * （码已删除 / 计数已清零），否则一个「只是碰巧返回失败」的实现也能通过。
 */
class CaptchaTest extends BaseIntegrationTest {

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private CaptchaProperties captchaProperties;

    // ---------- 发送：正常路径 ----------

    @Test
    @DisplayName("发送验证码成功：落码 + 6 位数字 + TTL 符合配置，响应体不含验证码")
    void send_success() throws Exception {
        String email = uniqueEmail("cap-send");
        MvcResult result = sendCaptcha(CaptchaScene.REGISTER, email);

        assertThat(businessCode(result)).isEqualTo(200);
        // 非调试模式下响应里不能出现任何可用信息
        assertThat(dataOf(result).isNull()
                || dataOf(result).path("code").isMissingNode()
                || dataOf(result).path("code").isNull()).isTrue();

        String code = codeInRedis(CaptchaScene.REGISTER, email);
        assertThat(code).isNotNull().hasSize(6).containsOnlyDigits();

        Long ttl = stringRedisTemplate.getExpire(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email),
                TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(captchaProperties.ttlSeconds() - 10, captchaProperties.ttlSeconds());

        // 间隔标记与当日计数一并写入（机制层断言：限流不是"看起来像"限流）
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.limit(CaptchaScene.REGISTER, email)))
                .isTrue();
        assertThat(stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.daily(CaptchaScene.REGISTER, email)))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("重发同一场景：覆盖旧码并清零错误计数")
    void send_resend_resetsAttempts() throws Exception {
        String email = uniqueEmail("cap-resend");
        sendCaptcha(CaptchaScene.REGISTER, email);

        // 先制造 1 次失败
        expectVerifyFailure(CaptchaScene.REGISTER, email, "000000", ResultCode.CAPTCHA_INVALID);
        assertThat(stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.attempt(CaptchaScene.REGISTER, email)))
                .isEqualTo("1");

        elapseSendInterval(CaptchaScene.REGISTER, email);
        sendCaptcha(CaptchaScene.REGISTER, email);

        // 新码应享有完整的 5 次机会，而不是沿用旧计数
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.attempt(CaptchaScene.REGISTER, email)))
                .as("重发验证码必须清零错误计数")
                .isFalse();
        // 不断言「新旧码不同」：那是一个 1e-6 概率的偶然断言，覆盖语义由脚本的 SET 保证
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isNotNull().hasSize(6);
    }

    // ---------- 发送：限流 ----------

    @Test
    @DisplayName("60 秒内重复发送被拒（1027），且旧码与计数不受影响")
    void send_withinInterval_rejected() throws Exception {
        String email = uniqueEmail("cap-gap");
        sendCaptcha(CaptchaScene.REGISTER, email);
        String firstCode = codeInRedis(CaptchaScene.REGISTER, email);

        MvcResult second = sendCaptcha(CaptchaScene.REGISTER, email);

        assertThat(businessCode(second)).isEqualTo(ResultCode.CAPTCHA_SEND_TOO_FREQUENT.getCode());
        // 被限流时不得落码、不得计数——否则限流只是"多返一个错误码"而已
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isEqualTo(firstCode);
        assertThat(stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.daily(CaptchaScene.REGISTER, email)))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("当日发送超限被拒（1027），且不覆盖仍有效的旧码")
    void send_dailyLimit_rejected() throws Exception {
        String email = uniqueEmail("cap-daily");
        sendCaptcha(CaptchaScene.REGISTER, email);
        String firstCode = codeInRedis(CaptchaScene.REGISTER, email);

        // 直接把当日计数拉到上限（比真发 10 次快且不受 60s 间隔干扰）
        stringRedisTemplate.opsForValue().set(CaptchaRedisKeys.daily(CaptchaScene.REGISTER, email),
                String.valueOf(captchaProperties.getDailyLimit()), java.time.Duration.ofHours(1));
        elapseSendInterval(CaptchaScene.REGISTER, email);

        MvcResult result = sendCaptcha(CaptchaScene.REGISTER, email);

        assertThat(businessCode(result)).isEqualTo(ResultCode.CAPTCHA_SEND_TOO_FREQUENT.getCode());
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isEqualTo(firstCode);
    }

    @Test
    @DisplayName("IP 维度日限：同一 IP 超限后拒绝（1027），换邮箱也绕不过去")
    void send_ipDailyLimit_blocksRegardlessOfEmail() throws Exception {
        // 用固定假 IP：与其它用例共享的 127.0.0.1 隔离开，避免互相消耗额度
        String ip = "203.0.113.7";
        String email = uniqueEmail("cap-ip-quota");
        // 把该 IP 的当日计数推到远超任何配置上限（比真发 N 次快，也不受 60s 间隔干扰）
        stringRedisTemplate.opsForValue()
                .set(CaptchaRedisKeys.ipDaily(ip), "999999", java.time.Duration.ofHours(1));

        MvcResult result = sendCaptchaFrom(ip, CaptchaScene.REGISTER, email);

        assertThat(businessCode(result))
                .as("只按邮箱限流等于没限——换邮箱即有新额度，所以 IP 维度必须独立生效")
                .isEqualTo(ResultCode.CAPTCHA_SEND_TOO_FREQUENT.getCode());
        // 机制层：IP 判定先于一切写操作 ⇒ 新邮箱既不落码、也不占 60s 间隔、不计入邮箱日限
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email))).isFalse();
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.limit(CaptchaScene.REGISTER, email))).isFalse();
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.daily(CaptchaScene.REGISTER, email))).isFalse();
    }

    @Test
    @DisplayName("IP 维度日限只约束该 IP：换 IP 照常发送，且正常发送会累计到该 IP 计数")
    void send_ipDailyLimit_isPerIp() throws Exception {
        String blockedIp = "203.0.113.8";
        stringRedisTemplate.opsForValue()
                .set(CaptchaRedisKeys.ipDaily(blockedIp), "999999", java.time.Duration.ofHours(1));

        String email = uniqueEmail("cap-ip-other");
        assertThat(businessCode(sendCaptchaFrom("203.0.113.9", CaptchaScene.REGISTER, email))).isEqualTo(200);
        assertThat(stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.ipDaily("203.0.113.9")))
                .as("该 IP 的当日计数应为 1（IP 计数统计的是真正发出去的请求）")
                .isEqualTo("1");
        assertThat(stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.ipDaily(blockedIp)))
                .as("被限流的 IP 计数不受影响").isEqualTo("999999");
    }

    // ---------- 发送：场景前置条件 ----------

    @Test
    @DisplayName("注册场景：邮箱已注册时拒绝发码（1001）")
    void send_registerScene_emailAlreadyRegistered() throws Exception {
        String email = uniqueEmail("cap-reg-dup");
        register(email, "abc123");

        MvcResult result = sendCaptcha(CaptchaScene.REGISTER, email);

        assertThat(businessCode(result)).isEqualTo(ResultCode.EMAIL_ALREADY_REGISTERED.getCode());
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isNull();
    }

    @Test
    @DisplayName("忘记密码场景：邮箱未注册时不落码，但响应与成功完全一致（防枚举）")
    void send_forgotScene_unregisteredEmail_looksLikeSuccess() throws Exception {
        String email = uniqueEmail("cap-forgot-none");

        MvcResult result = sendCaptcha(CaptchaScene.FORGOT_PASSWORD, email);

        assertThat(businessCode(result)).isEqualTo(200);
        assertThat(dataOf(result).isNull()).isTrue();
        // 机制层：确实没有落码（不投递、不写 Redis），只是对外不可区分
        assertThat(codeInRedis(CaptchaScene.FORGOT_PASSWORD, email)).isNull();
    }

    @Test
    @DisplayName("忘记密码场景：邮箱已注册时正常落码")
    void send_forgotScene_registeredEmail_storesCode() throws Exception {
        String email = uniqueEmail("cap-forgot-ok");
        register(email, "abc123");

        MvcResult result = sendCaptcha(CaptchaScene.FORGOT_PASSWORD, email);

        assertThat(businessCode(result)).isEqualTo(200);
        assertThat(codeInRedis(CaptchaScene.FORGOT_PASSWORD, email)).isNotNull().hasSize(6);
    }

    @Test
    @DisplayName("改密场景：未登录返回 401")
    void send_changePasswordScene_anonymous_unauthorized() throws Exception {
        MvcResult result = sendCaptcha(CaptchaScene.CHANGE_PASSWORD, uniqueEmail("cap-cp-anon"));

        // 这里判的是**业务码**而不是 HTTP 状态：/auth/captcha 在 URL 层是 permitAll，
        // 未登录由业务层（SecurityUtils）判定，因此走「HTTP 200 + body 业务码 401」的统一约定，
        // 而不是 URL 级鉴权那种真正的 HTTP 401（前端按业务码分支即可，两种都能识别）
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(businessCode(result)).isEqualTo(401);
    }

    @Test
    @DisplayName("改密场景：邮箱非本人返回 1032")
    void send_changePasswordScene_otherEmail_rejected() throws Exception {
        LoginSession me = loginGetAuth(uniqueEmail("cap-cp-me"), "abc123");
        String other = uniqueEmail("cap-cp-other");
        register(other, "abc123");

        MvcResult result = sendCaptcha(CaptchaScene.CHANGE_PASSWORD, other, me.token());

        assertThat(businessCode(result)).isEqualTo(ResultCode.CAPTCHA_EMAIL_MISMATCH.getCode());
        assertThat(codeInRedis(CaptchaScene.CHANGE_PASSWORD, other)).isNull();
    }

    @Test
    @DisplayName("改密场景：本人邮箱正常落码")
    void send_changePasswordScene_self_success() throws Exception {
        LoginSession me = loginGetAuth(uniqueEmail("cap-cp-self"), "abc123");

        MvcResult result = sendCaptcha(CaptchaScene.CHANGE_PASSWORD, me.email(), me.token());

        assertThat(businessCode(result)).isEqualTo(200);
        assertThat(codeInRedis(CaptchaScene.CHANGE_PASSWORD, me.email())).isNotNull().hasSize(6);
    }

    // ---------- 发送：参数校验 ----------

    @Test
    @DisplayName("场景取值非法返回 400（而非 500）")
    void send_invalidScene_badRequest() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"NOT_A_SCENE\",\"email\":\"" + uniqueEmail("cap-bad") + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(businessCode(result)).isEqualTo(400);
    }

    @Test
    @DisplayName("邮箱格式非法返回 400")
    void send_invalidEmail_badRequest() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"REGISTER\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(businessCode(result)).isEqualTo(400);
    }

    // ---------- 校验：机制层（直连服务） ----------

    @Test
    @DisplayName("校验成功即用后即焚：同一张码不能二次使用")
    void verify_success_burnsCode() throws Exception {
        String email = uniqueEmail("cap-burn");
        sendCaptcha(CaptchaScene.REGISTER, email);
        String code = codeInRedis(CaptchaScene.REGISTER, email);

        captchaService.verify(CaptchaScene.REGISTER, email, code);

        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email)))
                .as("校验成功后验证码必须立即删除")
                .isFalse();
        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.attempt(CaptchaScene.REGISTER, email)))
                .isFalse();

        // 重放同一张码 → 无效
        expectVerifyFailure(CaptchaScene.REGISTER, email, code, ResultCode.CAPTCHA_INVALID);
    }

    @Test
    @DisplayName("错够次数即作废：第 N 次返回 1029，且码与计数一并清除")
    void verify_wrongAttempts_locksAndBurnsCode() throws Exception {
        String email = uniqueEmail("cap-lock");
        sendCaptcha(CaptchaScene.REGISTER, email);
        int maxAttempts = captchaProperties.getMaxAttempts();

        for (int i = 1; i < maxAttempts; i++) {
            expectVerifyFailure(CaptchaScene.REGISTER, email, "000000", ResultCode.CAPTCHA_INVALID);
        }
        // 第 maxAttempts 次：超限作废
        expectVerifyFailure(CaptchaScene.REGISTER, email, "000000", ResultCode.CAPTCHA_ATTEMPT_EXCEEDED);

        assertThat(stringRedisTemplate.hasKey(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email)))
                .as("错次超限后验证码必须作废")
                .isFalse();
        // 作废后即便拿到正确的原码也无法通过（码已被删除）
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isNull();
    }

    @Test
    @DisplayName("验证码不存在或已过期：统一返回 1028")
    void verify_missingCode_invalid() {
        expectVerifyFailure(CaptchaScene.REGISTER, uniqueEmail("cap-miss"), "123456", ResultCode.CAPTCHA_INVALID);
    }

    @Test
    @DisplayName("过期等价于不存在：删掉码键后校验失败（且不区分文案）")
    void verify_expired_invalid() throws Exception {
        String email = uniqueEmail("cap-expire");
        sendCaptcha(CaptchaScene.REGISTER, email);
        String code = codeInRedis(CaptchaScene.REGISTER, email);

        // 模拟自然过期（TTL 5 分钟无法在用例里等待）
        stringRedisTemplate.delete(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email));

        expectVerifyFailure(CaptchaScene.REGISTER, email, code, ResultCode.CAPTCHA_INVALID);
    }

    @Test
    @DisplayName("跨场景复用被拒：注册码不能通过忘记密码的校验")
    void verify_crossScene_rejected() throws Exception {
        String email = uniqueEmail("cap-cross");
        sendCaptcha(CaptchaScene.REGISTER, email);
        String registerCode = codeInRedis(CaptchaScene.REGISTER, email);

        expectVerifyFailure(CaptchaScene.FORGOT_PASSWORD, email, registerCode, ResultCode.CAPTCHA_INVALID);

        // 且原场景的码未被这次失败消耗掉（跨场景失败不应影响本场景）
        assertThat(codeInRedis(CaptchaScene.REGISTER, email)).isEqualTo(registerCode);
    }

    @Test
    @DisplayName("空验证码按无效处理（不落到 Redis、不抛 500）")
    void verify_blankCode_invalid() {
        String email = uniqueEmail("cap-blank");
        expectVerifyFailure(CaptchaScene.REGISTER, email, null, ResultCode.CAPTCHA_INVALID);
        expectVerifyFailure(CaptchaScene.REGISTER, email, "  ", ResultCode.CAPTCHA_INVALID);
    }

    // ---------- 辅助 ----------

    private MvcResult sendCaptcha(CaptchaScene scene, String email) throws Exception {
        return sendCaptcha(scene, email, null);
    }

    private MvcResult sendCaptcha(CaptchaScene scene, String email, String token) throws Exception {
        var request = post("/auth/captcha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scene\":\"" + scene.name() + "\",\"email\":\"" + email + "\"}");
        if (token != null) {
            request.header("Authorization", bearerHeader(token));
        }
        return mockMvc.perform(request).andReturn();
    }

    /** 模拟「60 秒发送间隔已过」：删掉间隔标记，避免用例被真实时钟卡住 */
    private void elapseSendInterval(CaptchaScene scene, String email) {
        stringRedisTemplate.delete(CaptchaRedisKeys.limit(scene, email));
    }

    /** 以指定客户端 IP 发码（MockMvc 默认 remoteAddr 是 127.0.0.1，这里显式覆盖以便隔离 IP 额度） */
    private MvcResult sendCaptchaFrom(String clientIp, CaptchaScene scene, String email) throws Exception {
        return mockMvc.perform(post("/auth/captcha")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + scene.name() + "\",\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    private String codeInRedis(CaptchaScene scene, String email) {
        return stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.code(scene, email));
    }

    private int businessCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** 断言校验失败，并精确到业务码（1028 与 1029 语义不同，不能只看"失败了"） */
    private void expectVerifyFailure(CaptchaScene scene, String email, String code, ResultCode expected) {
        Throwable thrown = catchThrowable(() -> captchaService.verify(scene, email, code));
        assertThat(thrown)
                .as("期望校验失败并返回 %s", expected)
                .isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getCode()).isEqualTo(expected.getCode());
    }
}
