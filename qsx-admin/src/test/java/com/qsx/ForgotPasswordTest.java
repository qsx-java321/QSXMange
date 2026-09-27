package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.security.session.AuthRedisKeys;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 忘记密码（匿名重置）测试。
 *
 * <p>核心断言有两个层次：业务码（能不能重置）+ **机制层**（重置后旧令牌是否真的失效）。
 * 只断言「旧令牌 401」是不够的——每请求查库的兜底会让漏实现的代码也返回 401，
 * 必须同时断言 Redis 三键已消失。
 */
class ForgotPasswordTest extends BaseIntegrationTest {

    @Test
    @DisplayName("重置成功：旧令牌全部失效（Redis 键消失）、新密码可登录、旧密码不可")
    void forgotPassword_success_burnsSessions() throws Exception {
        String email = uniqueEmail("fp-ok");
        LoginSession session = loginGetAuth(email, "abc123");
        String code = captchaCode(CaptchaScene.FORGOT_PASSWORD, email);

        MvcResult result = forgotPassword(email, code, "newpass123", "newpass123");

        assertThat(businessCode(result)).isEqualTo(200);

        // 机制层断言：会话三键必须真的被清掉，而不是"碰巧 401"
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.token())))
                .as("旧 access token 键必须消失").isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken())))
                .as("旧 refresh token 键必须消失").isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId())))
                .as("会话索引必须消失").isFalse();

        // 旧 access token 立即 401
        MvcResult me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(401);

        // 新密码可登录、旧密码不可
        assertThat(loginCode(email, "newpass123")).isEqualTo(200);
        assertThat(loginCode(email, "abc123")).isEqualTo(1002);
    }

    @Test
    @DisplayName("验证码错误：拒绝重置，且密码不变")
    void forgotPassword_wrongCaptcha() throws Exception {
        String email = uniqueEmail("fp-wrong");
        loginGetAuth(email, "abc123");
        captchaCode(CaptchaScene.FORGOT_PASSWORD, email);   // 真实发码，但提交错的

        MvcResult result = forgotPassword(email, "000001", "newpass123", "newpass123");

        assertThat(businessCode(result)).isEqualTo(1028);
        // 密码未被改动：原密码仍能登录
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("两次输入的新密码不一致：1030（而不是笼统的 400）")
    void forgotPassword_passwordMismatch() throws Exception {
        String email = uniqueEmail("fp-mismatch");
        loginGetAuth(email, "abc123");
        String code = captchaCode(CaptchaScene.FORGOT_PASSWORD, email);

        MvcResult result = forgotPassword(email, code, "newpass123", "newpass456");

        assertThat(businessCode(result)).isEqualTo(1030);
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("防枚举：邮箱未注册与验证码错误返回完全一致的响应")
    void forgotPassword_unregisteredEmail_sameResponse() throws Exception {
        String registered = uniqueEmail("fp-enum-reg");
        loginGetAuth(registered, "abc123");

        MvcResult unknown = forgotPassword(uniqueEmail("fp-enum-none"), "123456", "newpass123", "newpass123");
        MvcResult wrongCode = forgotPassword(registered, "123456", "newpass123", "newpass123");

        JsonNode a = readJson(unknown);
        JsonNode b = readJson(wrongCode);
        assertThat(a.path("code").asInt()).isEqualTo(b.path("code").asInt());
        assertThat(a.path("message").asText()).isEqualTo(b.path("message").asText());
    }

    @Test
    @DisplayName("新密码强度不足：400（与注册同一套规则）")
    void forgotPassword_weakPassword() throws Exception {
        String email = uniqueEmail("fp-weak");
        loginGetAuth(email, "abc123");
        String code = captchaCode(CaptchaScene.FORGOT_PASSWORD, email);

        MvcResult result = forgotPassword(email, code, "abcdef", "abcdef");

        assertThat(businessCode(result)).isEqualTo(400);
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("缺验证码：400")
    void forgotPassword_missingCaptcha() throws Exception {
        String email = uniqueEmail("fp-miss");
        loginGetAuth(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"newPassword\":\"newpass123\","
                                + "\"confirmPassword\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(businessCode(result)).isEqualTo(400);
    }

    // ---------- 辅助 ----------

    private MvcResult forgotPassword(String email, String captcha, String newPassword, String confirm) throws Exception {
        return mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"captcha\":\"" + captcha
                                + "\",\"newPassword\":\"" + newPassword
                                + "\",\"confirmPassword\":\"" + confirm + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 走真实发码接口取一张重置密码验证码 */
    private String captchaCode(CaptchaScene scene, String email) throws Exception {
        mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + scene.name() + "\",\"email\":\"" + email + "\"}"))
                .andReturn();
        String code = stringRedisTemplate.opsForValue().get(CaptchaRedisKeys.code(scene, email));
        assertThat(code).as("发码应写入 Redis（场景 %s）", scene).isNotNull();
        return code;
    }

    private int loginCode(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        return businessCode(result);
    }

    private int businessCode(MvcResult result) throws Exception {
        return readJson(result).path("code").asInt();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
