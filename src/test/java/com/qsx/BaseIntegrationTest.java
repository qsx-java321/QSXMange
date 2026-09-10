package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：复用现有 QSXManager 库，测试前清空 sys_user，
 * 提供注册、登录取 token 等公共工具方法。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserMapper userMapper;

    @BeforeEach
    void cleanDatabase() {
        // 清空用户表，保证用例隔离
        userMapper.delete(null);
    }

    @AfterEach
    void tearDown() {
        userMapper.delete(null);
    }

    /**
     * 生成唯一邮箱，避免跨用例冲突
     */
    protected String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    /**
     * 注册一个用户，密码默认 abc123
     */
    protected MvcResult register(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    /**
     * 注册成功后，用账号密码登录并返回 token
     */
    protected String registerAndLoginGetToken(String email, String password) throws Exception {
        register(email, password);
        return loginGetToken(email, password);
    }

    /**
     * 登录并返回 token
     */
    protected String loginGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    /**
     * 生成带 Bearer 前缀的请求头
     */
    protected String bearerHeader(String token) {
        return "Bearer " + token;
    }
}