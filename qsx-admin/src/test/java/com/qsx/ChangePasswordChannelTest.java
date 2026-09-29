package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.security.session.AuthRedisKeys;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修改密码双通道测试。
 *
 * <p>通道 A（原密码）行为与改造前完全一致，本类补的是**回归锁定**：
 * 确认新增通道 B 没有把 A 的语义（含「改密后销毁会话」）改坏。
 */
class ChangePasswordChannelTest extends BaseIntegrationTest {

    @Test
    @DisplayName("通道 B（验证码）改密成功：会话三键消失、旧令牌 401、新旧密码登录矩阵正确")
    void channelB_success_burnsSessions() throws Exception {
        String email = uniqueEmail("cpb-ok");
        LoginSession session = loginGetAuth(email, "abc123");
        String code = changePasswordCaptcha(email, session.token());

        MvcResult result = changePassword(session.token(),
                "{\"captcha\":\"" + code + "\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(200);
        assertSessionsBurned(session);
        assertThat(loginCode(email, "newpass123")).isEqualTo(200);
        assertThat(loginCode(email, "abc123")).isEqualTo(1002);
    }

    @Test
    @DisplayName("通道 A（原密码）仍然可用，且照旧销毁会话（回归锁定）")
    void channelA_stillWorksAndBurnsSessions() throws Exception {
        String email = uniqueEmail("cpb-a");
        LoginSession session = loginGetAuth(email, "abc123");

        MvcResult result = changePassword(session.token(),
                "{\"oldPassword\":\"abc123\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(200);
        assertSessionsBurned(session);
        assertThat(loginCode(email, "newpass123")).isEqualTo(200);
    }

    @Test
    @DisplayName("双通道都传：拒绝（1031），密码不变")
    void bothChannels_rejected() throws Exception {
        String email = uniqueEmail("cpb-both");
        LoginSession session = loginGetAuth(email, "abc123");
        String code = changePasswordCaptcha(email, session.token());

        MvcResult result = changePassword(session.token(),
                "{\"oldPassword\":\"abc123\",\"captcha\":\"" + code + "\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(1031);
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("双通道都不传：拒绝（1031），密码不变")
    void neitherChannel_rejected() throws Exception {
        String email = uniqueEmail("cpb-none");
        LoginSession session = loginGetAuth(email, "abc123");

        MvcResult result = changePassword(session.token(), "{\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(1031);
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("通道 B 验证码错误：1028，密码不变")
    void channelB_wrongCaptcha() throws Exception {
        String email = uniqueEmail("cpb-wrong");
        LoginSession session = loginGetAuth(email, "abc123");
        String code = changePasswordCaptcha(email, session.token());
        // 取一个必然不同于真实码的值（真实码恰好是占位值时换一个）
        String wrong = code.equals(FALLBACK_CAPTCHA) ? "111111" : FALLBACK_CAPTCHA;

        MvcResult result = changePassword(session.token(),
                "{\"captcha\":\"" + wrong + "\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(1028);
        assertThat(loginCode(email, "abc123")).isEqualTo(200);
    }

    @Test
    @DisplayName("通道 B 不能拿别人的码：校验按当前登录用户的邮箱取码")
    void channelB_otherUsersCode_rejected() throws Exception {
        LoginSession me = loginGetAuth(uniqueEmail("cpb-me"), "abc123");
        LoginSession other = loginGetAuth(uniqueEmail("cpb-other"), "abc123");
        // 另一名用户给自己的邮箱发了一张改密码（因此这张码确实存在且有效）
        String otherCode = changePasswordCaptcha(other.email(), other.token());

        MvcResult result = changePassword(me.token(),
                "{\"captcha\":\"" + otherCode + "\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).isEqualTo(1028);
    }

    @Test
    @DisplayName("通道 B：登录用小写、库中存混合大小写时仍能改密（回归：验证码键必须折叠大小写）")
    void channelB_emailCaseMismatch_stillWorks() throws Exception {
        // 前置：构造出真实的大小写差异——注册用混合大小写（库中原样保存），登录用全小写
        String stored = uniqueEmail("cpb-Case");
        String lower = stored.toLowerCase(Locale.ROOT);
        assertThat(lower).as("前置：本用例必须真的存在大小写差异").isNotEqualTo(stored);

        register(stored, "abc123");
        // 库 collation 为 ci，小写邮箱照样登录成功；但 account.email() 仍是库中的混合大小写形态
        LoginSession session = loginGetAuth(lower, "abc123");
        assertThat(session.email()).as("库中保存的是注册时的原样大小写").isEqualTo(stored);

        // 以小写邮箱发码（业务允许：sendCaptcha 用 equalsIgnoreCase 判定为本人）
        String code = changePasswordCaptcha(lower, session.token());

        // 机制层：键必须落在折叠后的形态上。修复前发码侧按"客户端原样"、校验侧按"库中原样"，
        // 两端算出两个不同的键 ⇒ 同一张刚发出的码被判 1028
        assertThat(stringRedisTemplate.hasKey(
                CaptchaRedisKeys.code(CaptchaScene.CHANGE_PASSWORD, lower)))
                .as("验证码键按大小写折叠后的形态落库")
                .isTrue();

        MvcResult result = changePassword(session.token(),
                "{\"captcha\":\"" + code + "\",\"newPassword\":\"newpass123\"}");

        assertThat(businessCode(result)).as("修复前此处为 1028").isEqualTo(200);
        assertSessionsBurned(session);
        assertThat(loginCode(lower, "newpass123")).isEqualTo(200);
    }

    // ---------- 辅助 ----------

    private MvcResult changePassword(String token, String body) throws Exception {
        return mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 走真实发码接口给本人邮箱取一张改密验证码（scene=CHANGE_PASSWORD 需要登录态） */
    private String changePasswordCaptcha(String email, String token) throws Exception {
        mockMvc.perform(post("/auth/captcha")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + CaptchaScene.CHANGE_PASSWORD.name()
                                + "\",\"email\":\"" + email + "\"}"))
                .andReturn();
        String code = stringRedisTemplate.opsForValue()
                .get(CaptchaRedisKeys.code(CaptchaScene.CHANGE_PASSWORD, email));
        assertThat(code).as("改密场景发码应写入 Redis").isNotNull();
        return code;
    }

    /** 机制层断言：会话三键必须真的消失，且旧令牌立即 401（不能只看 401） */
    private void assertSessionsBurned(LoginSession session) throws Exception {
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.token()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId()))).isFalse();

        MvcResult me = mockMvc.perform(get("/auth/me")
                        .header("Authorization", bearerHeader(session.token())))
                .andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(401);
    }

    private int loginCode(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        return businessCode(result);
    }

    private int businessCode(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("code").asInt();
    }
}
