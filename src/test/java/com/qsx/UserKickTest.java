package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员强制登出（踢下线）与禁用保护测试
 */
class UserKickTest extends BaseIntegrationTest {

    /** 注册并登录一个绑定了 ADMIN 角色的管理员 */
    private LoginSession adminAuth() throws Exception {
        LoginSession admin = loginGetAuth(uniqueEmail("kick-admin"), "abc123");
        userService.assignRoles(admin.userId(), List.of(adminRoleId()));
        return admin;
    }

    // ---------- 强制登出 ----------

    @Test
    @DisplayName("管理员强踢普通用户后，其 refresh token 失效，需重新登录")
    void kick_user_invalidates_refresh() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession target = loginGetAuth(uniqueEmail("kick-target"), "abc123");

        MvcResult result = mockMvc.perform(post("/api/users/" + target.userId() + "/kick")
                        .header("Authorization", bearerHeader(admin.token())))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);

        // 被踢者 refresh 续期失败
        MvcResult refreshResult = postJson("/auth/refresh",
                Map.of("userId", target.userId(), "refreshToken", target.refreshToken()));
        assertThat(objectMapper.readTree(refreshResult.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);

        // 重新登录成功（账号未被禁用）
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(login.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("无强制登出权限的用户调用被拒（403）")
    void kick_without_permission_forbidden() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession normal = loginGetAuth(uniqueEmail("kick-normal"), "abc123");
        // admin 禁用保护后 normal 是普通用户，要用普通用户去踢需其有权限——此处普通用户无 user:kick
        mockMvc.perform(post("/api/users/" + admin.userId() + "/kick")
                        .header("Authorization", bearerHeader(normal.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("不可强制登出内置超管")
    void kick_admin_user_rejected() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession anotherAdmin = adminAuth();

        MvcResult result = mockMvc.perform(post("/api/users/" + anotherAdmin.userId() + "/kick")
                        .header("Authorization", bearerHeader(admin.token())))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1021);
    }

    @Test
    @DisplayName("不允许强制登出自己")
    void kick_self_rejected() throws Exception {
        LoginSession admin = adminAuth();

        MvcResult result = mockMvc.perform(post("/api/users/" + admin.userId() + "/kick")
                        .header("Authorization", bearerHeader(admin.token())))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1022);
    }

    @Test
    @DisplayName("强踢不存在的用户返回 1004")
    void kick_user_not_found() throws Exception {
        LoginSession admin = adminAuth();
        MvcResult result = mockMvc.perform(post("/api/users/999999/kick")
                        .header("Authorization", bearerHeader(admin.token())))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1004);
    }

    // ---------- 禁用保护 ----------

    @Test
    @DisplayName("禁用普通用户成功，其会话立即 401、登录 1003、续期 1019；解冻后恢复")
    void disable_and_enable_flow() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession target = loginGetAuth(uniqueEmail("dis-target"), "abc123");

        // 禁用
        MvcResult dis = mockMvc.perform(put("/api/users/" + target.userId())
                        .header("Authorization", bearerHeader(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(dis.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);

        // 旧 access token 请求立即 401（每请求实时查 status）
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(target.token())))
                .andExpect(status().isUnauthorized());
        // 登录被拒
        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"password\":\"abc123\"}"))
                .andReturn();
        assertThat(objectMapper.readTree(login.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1003);
        // refresh 被拒（实时查 status 兜底）
        MvcResult refresh = postJson("/auth/refresh",
                Map.of("userId", target.userId(), "refreshToken", target.refreshToken()));
        assertThat(objectMapper.readTree(refresh.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);

        // 解冻
        mockMvc.perform(put("/api/users/" + target.userId())
                        .header("Authorization", bearerHeader(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"status\":0}"))
                .andExpect(status().isOk());

        // 重新登录成功
        MvcResult relogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + target.email() + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(relogin.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("不可禁用内置超管用户")
    void disable_admin_user_rejected() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession anotherAdmin = adminAuth();

        MvcResult result = mockMvc.perform(put("/api/users/" + anotherAdmin.userId())
                        .header("Authorization", bearerHeader(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + anotherAdmin.email() + "\",\"status\":1}"))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1020);
    }

    @Test
    @DisplayName("不允许禁用自己")
    void disable_self_rejected() throws Exception {
        LoginSession admin = adminAuth();

        MvcResult result = mockMvc.perform(put("/api/users/" + admin.userId())
                        .header("Authorization", bearerHeader(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + admin.email() + "\",\"status\":1}"))
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1022);
    }

    @Test
    @DisplayName("删除用户联动清理 refresh 会话")
    void delete_user_cleans_refresh() throws Exception {
        LoginSession admin = adminAuth();
        LoginSession target = loginGetAuth(uniqueEmail("del-refresh"), "abc123");
        Long targetId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, target.email())).getId();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/users/" + targetId)
                        .header("Authorization", bearerHeader(admin.token())))
                .andExpect(status().isOk());

        // refresh 会话 key 已被删除；refresh 亦因用户被删而拒绝
        MvcResult refresh = postJson("/auth/refresh",
                Map.of("userId", targetId, "refreshToken", target.refreshToken()));
        assertThat(objectMapper.readTree(refresh.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);
    }
}