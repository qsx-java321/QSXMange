package com.qsx;

import com.qsx.security.session.AuthRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话生命周期测试：验证「吊销即时生效」这一改造核心目标。
 *
 * 改造前 access token 是无状态 JWT，登出/踢人/禁用/删除都只能删 refresh token，
 * 旧 access token 仍可用满 30 分钟；本类逐条断言这些场景下 access token 立即 401。
 *
 * 断言分两层：HTTP 401（行为）与 Redis 键缺失（机制）——
 * 只断言 401 会被每请求查库的 isEnabled() 兜底掩盖，测不出会话清理本身是否生效。
 */
class AuthSessionTest extends BaseIntegrationTest {

    // ---------- 登出 ----------

    @Test
    @DisplayName("登出后旧 access token 立即 401，会话三键清空")
    void logout_killsAccessTokenImmediately() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("sess-logout"), "abc123");

        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/logout").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.token()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();

        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 禁用 / 删除 ----------

    @Test
    @DisplayName("禁用用户后其 access token 立即 401，会话三键清空")
    void disable_killsAccessTokenImmediately() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("sess-disable"), "abc123");

        MvcResult result = mockMvc.perform(put("/api/users/" + session.userId())
                        .header("Authorization", bearerHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + session.email() + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.token()))).isFalse();

        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("普通资料编辑（status=0）不得清理会话——守卫写松会把改昵称变成强制登出")
    void normalEdit_keepsSession() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("sess-edit"), "abc123");

        mockMvc.perform(put("/api/users/" + session.userId())
                        .header("Authorization", bearerHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + session.email() + "\",\"nickname\":\"改名不改会话\",\"status\":0}"))
                .andExpect(status().isOk());

        // 会话仍在，令牌照常可用
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId()))).isTrue();
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());
    }

    // ---------- 改密 ----------

    @Test
    @DisplayName("改密后旧 access 与旧 refresh 均立即失效，新密码可登录")
    void changePassword_killsSession() throws Exception {
        String email = uniqueEmail("sess-pwd");
        LoginSession session = loginGetAuth(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(session.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);

        // 会话被清理：旧 access token 立即 401，旧 refresh token 无法续期
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(session.userId()))).isFalse();
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isUnauthorized());
        assertThat(objectMapper.readTree(postJson("/auth/refresh",
                Map.of("refreshToken", session.refreshToken())).getResponse().getContentAsString())
                .path("code").asInt()).isEqualTo(1019);

        // 新密码可重新登录
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"newpass123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(login.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    // ---------- 与用户数据解耦 ----------

    @Test
    @DisplayName("改邮箱后旧 access token 仍可用（随机串不含用户数据，不受用户行变更影响）")
    void emailChange_keepsAccessToken() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("sess-email"), "abc123");
        String newEmail = uniqueEmail("sess-email-new");

        mockMvc.perform(put("/api/users/" + session.userId())
                        .header("Authorization", bearerHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"status\":0}"))
                .andExpect(status().isOk());

        // 令牌按 userId 映射身份，改邮箱不影响（改造前 JWT 的 subject=email 会当场失配）
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(newEmail));
    }

    // ---------- 单端覆盖 ----------

    @Test
    @DisplayName("再次登录：旧 access token 与旧 refresh token 全部立即失效")
    void relogin_killsPreviousTokens() throws Exception {
        String email = uniqueEmail("sess-relogin");
        register(email, "abc123");
        LoginSession first = loginGetAuth(email, "abc123");
        LoginSession second = loginGetAuth(email, "abc123");

        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(first.token())))
                .andExpect(status().isUnauthorized());
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(first.token()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(first.refreshToken()))).isFalse();

        // 新会话可用，且会话索引指向新令牌
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(second.token())))
                .andExpect(status().isOk());
        assertThat(stringRedisTemplate.opsForValue().get(AuthRedisKeys.at(second.token())))
                .isEqualTo(String.valueOf(second.userId()));
    }
}
