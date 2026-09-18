package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证中心测试：注册、登录、当前用户、修改密码
 */
class AuthControllerTest extends BaseIntegrationTest {

    // ---------- 注册 ----------

    @Test
    @DisplayName("注册成功")
    void register_success() throws Exception {
        String email = uniqueEmail("reg-ok");
        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.path("code").asInt()).isEqualTo(200);

        // 库中存在，status=0
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email));
        assertThat(user).isNotNull();
        assertThat(user.getStatus()).isEqualTo(0);
        assertThat(user.getPassword()).startsWith("$2");
    }

    @Test
    @DisplayName("重复注册被拦截")
    void register_duplicateEmail() throws Exception {
        String email = uniqueEmail("reg-dup");
        register(email, "abc123");
        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1001);
    }

    @Test
    @DisplayName("注册缺邮箱")
    void register_missingEmail() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("注册非法邮箱格式")
    void register_invalidEmail() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"abc\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("注册弱密码（纯字母）")
    void register_weakPassword() throws Exception {
        String email = uniqueEmail("reg-weak");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abcdef\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    // ---------- 登录 ----------

    @Test
    @DisplayName("登录成功返回 token")
    void login_success() throws Exception {
        String email = uniqueEmail("login-ok");
        register(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("token").asText()).isNotBlank();
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("登录密码错误")
    void login_wrongPassword() throws Exception {
        String email = uniqueEmail("login-wrong");
        register(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1002);
    }

    @Test
    @DisplayName("登录禁用的账号被拒")
    void login_disabledUser() throws Exception {
        // 通过用户管理接口新增一个禁用账号（status=1）
        String disabledEmail = uniqueEmail("disabled");
        String adminToken = adminToken();

        // 新增禁用用户
        MvcResult addResult = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1003);
    }

    // ---------- 当前用户 ----------

    @Test
    @DisplayName("获取当前登录用户")
    void me_success() throws Exception {
        String email = uniqueEmail("me-ok");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(get("/auth/me")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    // ---------- 修改密码 ----------

    @Test
    @DisplayName("修改密码成功，旧密码失效新密码可用")
    void changePassword_success() throws Exception {
        String email = uniqueEmail("cp-ok");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);

        // 旧密码登录失败
        MvcResult oldLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode oldJson = objectMapper.readTree(oldLogin.getResponse().getContentAsString());
        assertThat(oldJson.path("code").asInt()).isEqualTo(1002);

        // 新密码登录成功
        MvcResult newLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode newJson = objectMapper.readTree(newLogin.getResponse().getContentAsString());
        assertThat(newJson.path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("修改密码原密码错误")
    void changePassword_wrongOld() throws Exception {
        String email = uniqueEmail("cp-wrong");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong1\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1006);
    }

    @Test
    @DisplayName("改密无 token 返回 401")
    void changePassword_noToken() throws Exception {
        mockMvc.perform(post("/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isUnauthorized());
    }
}