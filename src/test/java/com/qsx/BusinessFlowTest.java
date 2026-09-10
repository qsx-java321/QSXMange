package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 关键业务闭环测试：注册→登录→me→改密→删除→释放邮箱→重注册
 */
class BusinessFlowTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("完整闭环：注册→登录→me→改密→新密码重新登录")
    void full_loop() throws Exception {
        String email = uniqueEmail("loop");
        register(email, "abc123");

        // 登录拿 token
        String token = loginGetToken(email, "abc123");
        assertThat(token).isNotBlank();

        // me
        MvcResult me = mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meJson = objectMapper.readTree(me.getResponse().getContentAsString());
        assertThat(meJson.path("data").path("email").asText()).isEqualTo(email);

        // 改密
        MvcResult cp = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"newpass1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(cp.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200);

        // 新密码重新登录
        String newToken = loginGetToken(email, "newpass1");
        assertThat(newToken).isNotBlank();
    }

    @Test
    @DisplayName("核心特性：逻辑删除释放邮箱，同邮箱可重新注册登录")
    void delete_release_email() throws Exception {
        String email = uniqueEmail("release");

        // 注册用户 A
        register(email, "abc123");
        long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 用管理员 token 删除用户 A（管理员本身是另一账号）
        String adminToken = registerAndLoginGetToken(uniqueEmail("admin"), "abc123");
        MvcResult del = mockMvc.perform(delete("/api/users/" + userId)
                        .header("Authorization", bearerHeader(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(del.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200);

        // 数据库中 A 应带 #deleted_ 后缀且 deleted=1（用 JdbcTemplate 绕过逻辑删除过滤）
        Map<String, Object> deletedRow = jdbcTemplate.queryForMap(
                "SELECT email, deleted FROM sys_user WHERE id = ?", userId);
        assertThat(deletedRow.get("deleted")).isEqualTo(1);
        assertThat(deletedRow.get("email")).asString().startsWith(email).contains("#deleted_");

        // 原始邮箱可再次注册（用户 B）
        register(email, "xyz789");

        // 用户 B 可登录
        String tokenB = loginGetToken(email, "xyz789");
        assertThat(tokenB).isNotBlank();

        // 数据库中应为两条：A(deleted=1) + B(deleted=0)，B 用原始邮箱（MyBatis-Plus 仅返回未删除）
        List<User> alive = userMapper.selectList(null);
        User b = alive.stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow();
        assertThat(b.getDeleted()).isEqualTo(0);
        assertThat(b.getId()).isNotEqualTo(userId);
    }

    @Test
    @DisplayName("禁用用户登录被拒")
    void disabled_login_rejected() throws Exception {
        // 管理员新增禁用用户
        String disabledEmail = uniqueEmail("blocked");
        String adminToken = registerAndLoginGetToken(uniqueEmail("admin"), "abc123");
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\",\"status\":1}"))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1003);
    }
}