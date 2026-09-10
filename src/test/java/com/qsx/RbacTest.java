package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.Permission;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.PermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 权限测试：超管/普通用户/细粒度权限/即时生效/幂等
 */
class RbacTest extends BaseIntegrationTest {

    @Autowired
    private PermissionMapper permissionMapper;

    private MvcResult createRole(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"测试角色-" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long roleId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long assignPermissions(String token, long roleId, Long... permissionIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < permissionIds.length; i++) {
            if (i > 0) ids.append(",");
            ids.append(permissionIds[i]);
        }
        mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
        return roleId;
    }

    private void assignRoles(String token, long userId, long... roleIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < roleIds.length; i++) {
            if (i > 0) ids.append(",");
            ids.append(roleIds[i]);
        }
        mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
    }

    private long userIdByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
    }

    /** 按权限码查询权限ID（重复调用返回同一支数据） */
    private long permIdByCode(String code) {
        Permission p = permissionMapper.selectOne(
                new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
        if (p == null) {
            throw new IllegalStateException("未找到权限码 " + code + "，请先导入新版 init.sql");
        }
        return p.getId();
    }

    // ---------- 超管 ----------

    @Test
    @DisplayName("超级管理员可访问角色/权限接口")
    void admin_access_ok() throws Exception {
        String token = adminToken();
        MvcResult roles = mockMvc.perform(get("/api/roles").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(roles.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200);

        MvcResult perms = mockMvc.perform(get("/api/permissions").header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(perms.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200);
    }

    // ---------- 普通用户无权 ----------

    @Test
    @DisplayName("普通用户（无角色）访问用户/角色接口返回403")
    void normal_user_forbidden() throws Exception {
        String token = registerAndLoginGetToken(uniqueEmail("plain"), "abc123");

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(token)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/roles").header("Authorization", bearerHeader(token)))
                .andExpect(status().isForbidden());
    }

    // ---------- 细粒度权限 ----------

    @Test
    @DisplayName("仅分配 user:page 的角色：能查用户、不能查角色")
    void fine_grained_permission() throws Exception {
        String admin = adminToken();

        // 建一个只含 user:page 的角色
        MvcResult created = createRole(admin, uniqueRoleCode());
        long roleId = roleId(created);
        assignPermissions(admin, roleId, permIdByCode("user:page"));

        // 建普通用户并绑定该角色
        String userEmail = uniqueEmail("rg-user");
        String userToken = registerAndLoginGetToken(userEmail, "abc123");
        assignRoles(admin, userIdByEmail(userEmail), roleId);

        // 该用户能查用户列表（有 user:page）
        MvcResult users = mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(users.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(200);

        // 不能查角色列表（无 role:page）
        mockMvc.perform(get("/api/roles").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }

    // ---------- 即时生效（每请求查库，无缓存） ----------

    @Test
    @DisplayName("移除角色权限后同一 token 立即失效")
    void permission_instant_effect() throws Exception {
        String admin = adminToken();

        MvcResult created = createRole(admin, uniqueRoleCode());
        long roleId = roleId(created);
        assignPermissions(admin, roleId, permIdByCode("user:page"));

        String userEmail = uniqueEmail("inst-user");
        String userToken = registerAndLoginGetToken(userEmail, "abc123");
        assignRoles(admin, userIdByEmail(userEmail), roleId);

        // 有权时访问成功
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());

        // 移除该角色所有权限，同一 token 再访问应立即 403（每请求查库）
        mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }

    // ---------- 幂等 ----------

    @Test
    @DisplayName("重复分配用户角色不产生重复关联")
    void assign_roles_idempotent() throws Exception {
        String admin = adminToken();
        long adminRole = adminRoleId();

        // 再建一个普通角色，重复分配两次
        MvcResult created = createRole(admin, uniqueRoleCode());
        long roleB = roleId(created);

        String userEmail = uniqueEmail("idem-user");
        register(userEmail, "abc123");
        long userId = userIdByEmail(userEmail);

        for (int i = 0; i < 2; i++) {
            assignRoles(admin, userId, adminRole, roleB);
        }

        List<UserRole> rels = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        // 整表替换后仅 2 条关联，无重复
        assertThat(rels).hasSize(2);
    }

    private String uniqueRoleCode() {
        return "ROLE_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}