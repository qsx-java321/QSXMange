package com.qsx;

import com.qsx.security.config.properties.AuthSessionProperties;
import com.qsx.security.session.AuthRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权放行测试：401 / 匿名放行 / 无效令牌 / 已吊销令牌 / TTL 接线
 *
 * 说明：改造后令牌有效性以 Redis 为准，「过期」不再是令牌自身的属性——
 * 过期与「从未签发」「已被吊销」在服务端表现完全一致（Redis 中无对应键），
 * 因此原「手搓过期 JWT」用例由「删掉 at 键后同一令牌立即 401」取代。
 */
class SecurityAccessTest extends BaseIntegrationTest {

    @Autowired
    private AuthSessionProperties authSessionProperties;

    @Test
    @DisplayName("匿名访问登录接口放行（返回业务而非401）")
    void anonymous_login_permitted() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"x@test.com\",\"password\":\"wrong1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名访问用户接口返回401")
    void anonymous_users_forbidden() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("伪造 token 返回401")
    void fake_token_unauthorized() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已在 Redis 中失效的 access token 立即返回401（改造前要等 JWT 自然过期）")
    void revoked_token_unauthorized() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("revoked"), "abc123");

        // 令牌有效时可正常访问
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        // 删掉 at 键：等价于踢人/登出/禁用/删除/改密之后的会话状态
        stringRedisTemplate.delete(AuthRedisKeys.at(session.token()));

        // 同一令牌立刻失效，无需等待 30 分钟
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录签发的 at 键 TTL 与配置一致")
    void loginAccessTokenTtlMatchesConfig() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("at-ttl"), "abc123");

        long configured = authSessionProperties.getAtTtl().toSeconds();
        assertThat(stringRedisTemplate.getExpire(AuthRedisKeys.at(session.token()), TimeUnit.SECONDS))
                .isBetween(configured - 5, configured);
    }

    @Test
    @DisplayName("有效 token 访问用户接口返回200（需 ADMIN 权限）")
    void valid_token_users_ok() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
    }
}
