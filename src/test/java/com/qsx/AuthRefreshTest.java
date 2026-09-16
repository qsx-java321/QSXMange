package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import com.qsx.security.session.AuthRedisKeys;
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
 * 刷新令牌测试：签发、轮换、失效、禁用/删除联动、绝对上限、单端登录。
 *
 * 入参契约：refresh 仅需 {refreshToken}（身份由服务端按 rt 反查，不再由客户端自报 userId）；
 * 令牌形态必须是 64 位小写 hex——形态非法返回 400，形态合法但未知/已失效返回 1019。
 */
class AuthRefreshTest extends BaseIntegrationTest {

    /** 形态合法（64 位 hex）但从未签发的令牌 */
    private static final String UNKNOWN_TOKEN = "f".repeat(64);

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
    @DisplayName("refresh 换取新令牌对，新 access 可用，旧 refresh 与旧 access 立即失效")
    void refresh_rotates_and_old_invalid() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-rotate"), "abc123");

        MvcResult refreshResult = postJson("/auth/refresh",
                Map.of("refreshToken", session.refreshToken()));
        JsonNode data = objectMapper.readTree(refreshResult.getResponse().getContentAsString()).path("data");
        assertThat(refreshResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();

        // 新 access token 可正常访问
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(data.path("token").asText())))
                .andExpect(status().isOk());

        // 旧 access token 随轮换立即失效（在途请求会 401，前端须保证刷新单飞）
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isUnauthorized());

        // 旧 refresh token 轮换后失效
        MvcResult oldResult = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
        JsonNode oldJson = objectMapper.readTree(oldResult.getResponse().getContentAsString());
        assertThat(oldJson.path("code").asInt()).isEqualTo(1019);

        // 新 refresh token 可继续续期（滑动）
        MvcResult againResult = postJson("/auth/refresh",
                Map.of("refreshToken", data.path("refreshToken").asText()));
        JsonNode againJson = objectMapper.readTree(againResult.getResponse().getContentAsString());
        assertThat(againJson.path("code").asInt()).isEqualTo(200);
    }

    // ---------- 异常分支 ----------

    @Test
    @DisplayName("形态合法但未签发的 refresh token 被拒（1019）")
    void refresh_unknown_token() throws Exception {
        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", UNKNOWN_TOKEN));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("形态非法的 refresh token 返回 400（不打 Redis）")
    void refresh_malformed_token() throws Exception {
        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", "forged-token-value"));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("refresh 参数缺失返回 400")
    void refresh_missing_param() throws Exception {
        MvcResult result = postJson("/auth/refresh", Map.of());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("用户行不存在时 refresh 被拒（会话键仍在，走查库兜底分支）")
    void refresh_user_not_found() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-nouser"), "abc123");
        Long userId = session.userId();

        // 直接逻辑删库，绕过会话清理——以构造「会话键还在但用户行已消失」的状态
        userMapper.deleteById(userId);

        // 前置确认：会话键确实还在，否则本用例会因「键不存在」而空过
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isTrue();

        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("禁用用户 refresh 被拒（会话键仍在，走查库兜底分支）")
    void refresh_disabled_user_rejected() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-dis"), "abc123");

        // 直接改库置为禁用，绕过 HTTP 禁用流程——以留住会话键，
        // 确保被验证的是「refresh 的查库状态兜底」而不是「键已被删」
        User user = userMapper.selectById(session.userId());
        user.setStatus(1);
        userMapper.updateById(user);

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isTrue();

        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1019);
    }

    @Test
    @DisplayName("登出后 refresh 失效")
    void logout_invalidates_refresh() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-logout"), "abc123");

        mockMvc.perform(post("/auth/logout").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
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
    @DisplayName("超过 30 天绝对上限，续期被拒并清空会话三键")
    void refresh_exceedsMaxLifetime() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("rt-max"), "abc123");
        String sessionKey = AuthRedisKeys.session(session.userId());

        // 篡改会话索引里的首次登录时间，模拟 31 天前登录
        stringRedisTemplate.opsForHash().put(sessionKey, "firstLoginTs",
                String.valueOf(System.currentTimeMillis() - 31L * 24 * 3600 * 1000));

        MvcResult result = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);

        // 会话被整体吊销：三键全清，旧令牌重放依旧 1019
        assertThat(stringRedisTemplate.hasKey(sessionKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.token()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();

        MvcResult again = postJson("/auth/refresh", Map.of("refreshToken", session.refreshToken()));
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);
    }

    // ---------- 单端登录 ----------

    @Test
    @DisplayName("单端登录：再次登录覆盖旧会话，旧 access 与旧 refresh 全部失效")
    void single_session_new_login_kills_old() throws Exception {
        String email = uniqueEmail("rt-single");
        register(email, "abc123");

        LoginSession first = loginGetAuth(email, "abc123");
        LoginSession second = loginGetAuth(email, "abc123");

        // 第一次会话的 refresh 已失效，第二次的可用
        assertThat(objectMapper.readTree(postJson("/auth/refresh",
                Map.of("refreshToken", first.refreshToken()))
                .getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1019);

        // 第一次会话的 access token 同样立即失效（单端登录对 AT 的保证）
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(first.token())))
                .andExpect(status().isUnauthorized());

        MvcResult ok = postJson("/auth/refresh", Map.of("refreshToken", second.refreshToken()));
        assertThat(objectMapper.readTree(ok.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }
}
