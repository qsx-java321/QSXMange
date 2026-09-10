package com.qsx;

import com.qsx.config.properties.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 鉴权放行测试：401 / 匿名放行 / 无效 token
 */
class SecurityAccessTest extends BaseIntegrationTest {

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("匿名访问登录接口放行（返回业务而非401）")
    void anonymous_login_permitted() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"email\":\"x@test.com\",\"password\":\"wrong1\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    @DisplayName("匿名访问用户接口返回401")
    void anonymous_users_forbidden() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    @DisplayName("伪造 token 返回401")
    void fake_token_unauthorized() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    @DisplayName("过期 token 返回401")
    void expired_token_unauthorized() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        // 构造早已过期的 token
        String expired = Jwts.builder()
                .subject("expired@test.com")
                .issuedAt(new Date(System.currentTimeMillis() - 100_000))
                .expiration(new Date(System.currentTimeMillis() - 50_000))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + expired))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    @DisplayName("有效 token 访问用户接口返回200（需 ADMIN 权限）")
    void valid_token_users_ok() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}