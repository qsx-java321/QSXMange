package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 刷新令牌（refresh token）测试：签发、轮换、失效、禁用联动、绝对上限
 */
class AuthRefreshTest extends BaseIntegrationTest {

    // ---------- 签发 ----------

    @Test
    @DisplayName("登录返回 access token 与 refresh token")
    void login_returns_refreshToken() throws Exception {
        String email = uniqueEmail("rt-ok");
        register(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();
        assertThat(data.path("userId").asLong()).isPositive();
    }

    // ---------- 轮换 ----------

    @Test
    @DisplayName("refresh 换取新令牌对，新 access 可用，旧 refresh 立即失效")
    void refresh_rotates_and_old_invalid() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-rotate"), "abc123");

        MvcResult refreshResult = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", session.refreshToken()));
        JsonNode data = objectMapper.readTree(refreshResult.getResponse().getContentAsString()).path("data");
        assertThat(refreshResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();

        // 新 access token 可正常访问
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(data.path("token").asText())))
                .andExpect(status().isOk());

        // 旧 refresh token 轮换后失效
        MvcResult oldResult = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", session.refreshToken()));
        JsonNode oldJson = objectMapper.readTree(oldResult.getResponse().getContentAsString());
        assertThat(oldJson.path("code").asInt()).isEqualTo(1019);

        // 新 refresh token 可继续续期（滑动）
        MvcResult againResult = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", data.path("refreshToken").asText()));
        JsonNode againJson = objectMapper.readTree(againResult.getResponse().getContentAsString());
        assertThat(againJson.path("code").asInt()).isEqualTo(200);
    }

    // ---------- 异常分支 ----------

    @Test
    @DisplayName("伪造 refresh token 被拒")
    void refresh_invalid_token() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-fake"), "abc123");
        MvcResult result = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", "forged-token-value"));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("refresh 传不存在的用户被拒")
    void refresh_user_not_found() throws Exception {
        MvcResult result = postJson("/auth/refresh",
                Map.of("userId", 999999L, "refreshToken", "whatever"));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("refresh 参数缺失返回 400")
    void refresh_missing_param() throws Exception {
        MvcResult result = postJson("/auth/refresh", Map.of("userId", 1L));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("禁用用户 refresh 被拒（状态实时校验兜底）")
    void refresh_disabled_user_rejected() throws Exception {
        LoginSession user = loginGetAuth(uniqueEmail("rt-dis"), "abc123");

        // 管理员禁用该用户（走 update status=1 接口）
        String adminToken = adminToken();
        Long userId = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, user.email())).getId();
        mockMvc.perform(put("/api/users/" + userId)
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.email() + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult result = postJson("/auth/refresh",
                Map.of("userId", userId, "refreshToken", user.refreshToken()));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("登出后 refresh 失效")
    void logout_invalidates_refresh() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-logout"), "abc123");

        mockMvc.perform(post("/auth/logout").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        MvcResult result = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", session.refreshToken()));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("登出未登录返回 401")
    void logout_no_token_unauthorized() throws Exception {
        mockMvc.perform(post("/auth/logout")).andExpect(status().isUnauthorized());
    }

    // ---------- 绝对上限 ----------

    @Test
    @DisplayName("超过 30 天绝对上限，续期被拒并吊销会话")
    void refresh_exceedsMaxLifetime() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-max"), "abc123");

        // 直接篡改 Redis 中的 firstLoginTs 模拟 31 天前首次登录
        String key = "qsx:auth:refresh:" + session.userId();
        String json = stringRedisTemplate.opsForValue().get(key);
        ObjectNode node = (ObjectNode) objectMapper.readTree(json);
        node.put("firstLoginTs", System.currentTimeMillis() - 31L * 24 * 3600 * 1000);
        stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(node));

        MvcResult result = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", session.refreshToken()));
        JsonNode ret = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(ret.path("code").asInt()).isEqualTo(1019);

        // 会话已被吊销：即便再次登录也会生成新会话，此处校验旧 refresh 无论如何不可用
        MvcResult again = postJson("/auth/refresh",
                Map.of("userId", session.userId(), "refreshToken", session.refreshToken()));
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);
    }

    @Test
    @DisplayName("单端登录：再次登录覆盖旧会话，旧 refresh 失效")
    void single_session_new_login_kills_old() throws Exception {
        String email = uniqueEmail("rt-single");
        register(email, "abc123");

        LoginSession first = loginGetAuth(email, "abc123");
        LoginSession second = loginGetAuth(email, "abc123");

        // 第一次会话的 refresh 已失效，第二次的可用
        assertThat(objectMapper.readTree(postJson("/auth/refresh",
                Map.of("userId", first.userId(), "refreshToken", first.refreshToken()))
                .getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1019);

        MvcResult ok = postJson("/auth/refresh",
                Map.of("userId", second.userId(), "refreshToken", second.refreshToken()));
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }
}