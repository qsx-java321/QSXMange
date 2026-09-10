package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
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
 * 集成测试基类：复用现有 QSXManager 库，测试前清空 sys_user / sys_user_role，
 * 提供注册、登录取 token、构造超管等公共工具方法。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    /** 内置超管角色编码（init.sql 预置） */
    protected static final String ADMIN_ROLE_CODE = "ADMIN";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserMapper userMapper;

    @Autowired
    protected UserRoleMapper userRoleMapper;

    @Autowired
    protected RoleMapper roleMapper;

    @BeforeEach
    void cleanDatabase() {
        // 清空用户表与用户-角色关联，保证用例隔离（角色/权限为常驻种子数据，不清理）
        userRoleMapper.delete(null);
        userMapper.delete(null);
    }

    @AfterEach
    void tearDown() {
        userRoleMapper.delete(null);
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

    /**
     * 查询内置超管角色的ID（init.sql 预置，测试期常驻）
     */
    protected long adminRoleId() throws Exception {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, ADMIN_ROLE_CODE));
        if (role == null) {
            throw new IllegalStateException("未找到内置角色 " + ADMIN_ROLE_CODE + "，请先导入新版 init.sql");
        }
        return role.getId();
    }

    /**
     * 构造一个绑定 ADMIN 超管角色的用户并返回其 token
     */
    protected String adminToken() throws Exception {
        String email = uniqueEmail("admin");
        String token = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(adminRoleId());
        userRoleMapper.insert(ur);
        return token;
    }
}