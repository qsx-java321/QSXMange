package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.PermissionConstants;
import com.qsx.domain.entity.Permission;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.PermissionMapper;
import com.qsx.security.session.AuthRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理 & 会话管理 —— 覆盖缺口/疑点专项测试（本次补测）。
 *
 * 背景：用户管理与会话管理已有集成测试覆盖主干路径；本类只补
 * 尚未被锁定的边界与保护疑点，不改任何生产代码：
 *   1. 创建接口 /api/users 的参数校验（弱密码/非法邮箱/缺邮箱/昵称超长）
 *   2. 分页参数 pageNum/pageSize 越界（UserQuery 无校验，锁定不 500）
 *   3. assignRoles 边界：不存在用户(1004)/不存在角色(1009 回滚)/无权限(403)
 *   4. 空角色分配 → 清空角色并即时回收权限（缓存失效）
 *   5. 删除用户联动清理会话的完整断言（session/at 键 + 401 + refresh 1019）
 *   6. delete() 保护补全回归：删除超管→1025、删除自己→1022（与 update=1020/kick=1021 同口径）
 */
class UserEdgeTest extends BaseIntegrationTest {

    @Autowired
    private PermissionMapper permissionMapper;

    // ================== 私有 helper（RbacTest/MenuTest 的同名方法为私有，不共享，本地复刻） ==================

    /** 创建角色（name 用 code 占位） */
    private MvcResult createRole(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"测试角色-" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 从创建角色响应中取 roleId */
    private long roleId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    /** 给角色分配权限（走真实 business 链路，超管携全部权限可调） */
    private void assignPermissions(String token, long roleId, Long... permissionIds) throws Exception {
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
    }

    /** 按权限码查权限 id */
    private long permIdByCode(String code) {
        Permission p = permissionMapper.selectOne(
                new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
        if (p == null) {
            throw new IllegalStateException("未找到权限码 " + code + "，请先导入新版 init.sql");
        }
        return p.getId();
    }

    /** 角色编码唯一 */
    private String uniqueRoleCode() {
        return "test-role-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 按 email 查 userId */
    private long userIdByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
    }

    /** 调用 assignRoles 接口并返回 body code */
    private int callAssignRoles(String token, long userId, Integer... roleIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (Integer r : roleIds) {
            if (ids.length() > 0) ids.append(",");
            ids.append(r);
        }
        MvcResult result = mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + ids + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    /** 该用户的角色关联数 */
    private long roleRelCount(long userId) {
        return userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
    }

    // ================== 1. 创建接口 /api/users 参数校验 ==================

    @Test
    @DisplayName("[补测] 创建用户弱密码(<6位)被参数校验拒绝且不落库")
    void create_weakPassword_rejected() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("create-weak");
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc12\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(400);
        assertThat(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email))).isNull();
    }

    @Test
    @DisplayName("[补测] 创建用户非法邮箱格式被拒且不落库")
    void create_invalidEmail_rejected() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"abc\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("[补测] 创建用户缺邮箱被拒且不落库")
    void create_missingEmail_rejected() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("[补测] 创建用户昵称超50被拒且不落库")
    void create_nicknameTooLong_rejected() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("create-lognick");
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\",\"nickname\":\"" + "n".repeat(51) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(400);
        assertThat(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email))).isNull();
    }

    // ================== 2. 分页参数越界（UserQuery 无校验，锁定不 500） ==================

    @Test
    @DisplayName("[补测] 分页 pageSize=0 不返回 500")
    void page_pageSizeZero() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(get("/api/users").param("pageSize", "0")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("[补测] 分页 pageSize=-1 不返回 500")
    void page_negativePageSize() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(get("/api/users").param("pageSize", "-1")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("[补测] 分页 pageSize=100000 超大不返回 500 且返回全部记录")
    void page_hugePageSize() throws Exception {
        String token = adminToken();
        JsonNode json = objectMapper.readTree(mockMvc.perform(get("/api/users")
                        .param("pageSize", "100000")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        // 超大 pageSize 应返回一页承载全部记录
        assertThat(json.path("data").path("records").size())
                .isEqualTo((int) json.path("data").path("total").asLong());
    }

    @Test
    @DisplayName("[补测] 分页 pageNum=0 不返回 500")
    void page_zeroPageNum() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(get("/api/users").param("pageNum", "0")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    // ================== 3. assignRoles 边界 ==================

    @Test
    @DisplayName("[补测] 给不存在的用户分配角色返回 1004")
    void assignRoles_unknown_user() throws Exception {
        String token = adminToken();
        assertThat(callAssignRoles(token, 99999L, (int) adminRoleId())).isEqualTo(1004);
    }

    @Test
    @DisplayName("[补测] 分配不存在的角色返回 1009，且不改动目标用户原角色（事务回滚）")
    void assignRoles_unknown_role_rollback() throws Exception {
        String token = adminToken();
        // 建一个可用的普通角色并绑定给目标用户
        long roleB = roleId(createRole(token, uniqueRoleCode()));
        String email = uniqueEmail("role-rollback");
        register(email, "abc123");
        long userId = userIdByEmail(email);
        assertThat(callAssignRoles(token, userId, (int) roleB)).isEqualTo(200);
        assertThat(roleRelCount(userId)).isEqualTo(1);

        // 再分配一个不存在的角色 → 1009，且原关联不被「先删后插」破坏（事务回滚）
        assertThat(callAssignRoles(token, userId, 99999)).isEqualTo(1009);
        assertThat(roleRelCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("[补测] 无 user:assign-role 权限的普通用户分配角色被拒（403）")
    void assignRoles_without_permission_forbidden() throws Exception {
        LoginSession admin = loginGetAuth(uniqueEmail("assign-forbid-admin"), "abc123");
        grantAdminByPresetSuperAdmin(admin.userId());
        // 目标用户（无 user:assign-role 权限）
        LoginSession normal = loginGetAuth(uniqueEmail("assign-forbid-user"), "abc123");

        mockMvc.perform(put("/api/users/" + normal.userId() + "/roles")
                        .header("Authorization", bearerHeader(normal.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    // ================== 3.5 闸 2：授予 ADMIN 需操作者本身是超管 ==================

    @Test
    @DisplayName("[补测] 非超管操作者（仅有 user:assign-role）授予 ADMIN 被拒 1034，且不落库")
    void grantAdmin_requiresAdminOperator_1034() throws Exception {
        // 造一个「只有 user:assign-role、本身不是超管」的操作者：
        // 这正是闸 2 要防的角色——管理员把授权能力下放给某人，此人再给自己或他人升超管
        long grantRole = roleId(createRole(presetAdminToken(), uniqueRoleCode()));
        assignPermissions(presetAdminToken(), grantRole, permIdByCode(PermissionConstants.USER_ASSIGN_ROLE));
        LoginSession operator = loginGetAuth(uniqueEmail("grant-admin-op"), "abc123");
        assertThat(callAssignRoles(presetAdminToken(), operator.userId(), (int) grantRole)).isEqualTo(200);

        LoginSession target = loginGetAuth(uniqueEmail("grant-admin-target"), "abc123");

        // 1) 给自己绑 ADMIN——自助提权最直接的路径（1033 只看目标、1017 只管导入，都拦不住它）
        assertThat(callAssignRoles(operator.token(), operator.userId(), (int) adminRoleId()))
                .as("非超管不得授予 ADMIN")
                .isEqualTo(1034);
        assertThat(roleRelCount(operator.userId())).as("仍是那个非 ADMIN 角色，且只有 1 条").isEqualTo(1);

        // 2) 给他人绑 ADMIN 同样被拒
        assertThat(callAssignRoles(operator.token(), target.userId(), (int) adminRoleId())).isEqualTo(1034);
        assertThat(roleRelCount(target.userId())).isZero();

        // 3) 回归：同一个人授普通角色仍然放行（闸 2 只拦 ADMIN）
        assertThat(callAssignRoles(operator.token(), target.userId(), (int) grantRole)).isEqualTo(200);
        assertThat(roleRelCount(target.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("[补测] 预置超管（本身持有 ADMIN）授予 ADMIN 放行——闸 2 的正向对照")
    void grantAdmin_byAdminOperator_allowed() throws Exception {
        LoginSession target = loginGetAuth(uniqueEmail("grant-admin-ok"), "abc123");

        assertThat(callAssignRoles(presetAdminToken(), target.userId(), (int) adminRoleId())).isEqualTo(200);
        assertThat(roleRelCount(target.userId())).isEqualTo(1);
    }

    // ================== 4. 空角色分配 → 清空 + 权限即时回收 ==================

    @Test
    @DisplayName("[补测] 空角色分配清空关联并即时回收权限（缓存失效）")
    void assignRoles_empty_clears_and_revokes() throws Exception {
        String admin = adminToken();
        // 构造一个仅含 user:page 的角色
        long roleId = roleId(createRole(admin, uniqueRoleCode()));
        assignPermissions(admin, roleId, permIdByCode(PermissionConstants.USER_PAGE));

        LoginSession u = loginGetAuth(uniqueEmail("empty-revoke"), "abc123");
        long userId = u.userId();
        String userToken = u.token();

        // 绑定：有 user:page → 可访问用户列表
        assertThat(callAssignRoles(admin, userId, (int) roleId)).isEqualTo(200);
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());

        // 空 roleIds → 整表替换为空
        assertThat(callAssignRoles(admin, userId)).isEqualTo(200);
        assertThat(roleRelCount(userId)).isZero();

        // 同一 token 权限即时回收（缓存失效后实时查库无权限 → 403）
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }

    // ================== 5. 删除联动清理会话（完整断言） ==================

    @Test
    @DisplayName("[补测] 删除用户后 session/at 键清空、旧 token 401、refresh 1019")
    void delete_cleans_session_full() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail("del-session-full");
        LoginSession target = loginGetAuth(email, "abc123");

        // 前置：会话键确实存在
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(target.userId()))).isTrue();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(target.token()))).isTrue();

        mockMvc.perform(delete("/api/users/" + target.userId())
                        .header("Authorization", bearerHeader(admin)))
                .andExpect(status().isOk());

        // 会话三键清空（AFTER_COMMIT 同步清理，接口返回时已生效）
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(target.userId()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(target.token()))).isFalse();

        // 旧 access 立即 401（用户行已被删）
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(target.token())))
                .andExpect(status().isUnauthorized());
        // 旧 refresh 无法续期
        MvcResult refresh = postJson("/auth/refresh", Map.of("refreshToken", target.refreshToken()));
        assertThat(objectMapper.readTree(refresh.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1019);
    }

    // ================== 6. delete() 保护补全回归 ==================

    @Test
    @DisplayName("[补测] delete() 保护 ADMIN 用户：删除超管返回 1025（与 update=1020/kick=1021 同口径）")
    void delete_admin_rejected() throws Exception {
        // 构造两个独立 ADMIN 测试用户（由预置超管经 HTTP 授权，见基类 grantAdminByPresetSuperAdmin）
        String email = uniqueEmail("del-super-a");
        String tokenA = registerAndLoginGetToken(email, "abc123");
        long idA = userIdByEmail(email);
        grantAdminByPresetSuperAdmin(idA);

        LoginSession b = loginGetAuth(uniqueEmail("del-super-b"), "abc123");
        grantAdminByPresetSuperAdmin(b.userId());

        // 删除 ADMIN 用户被拒（1025），不删任何数据
        MvcResult result = mockMvc.perform(delete("/api/users/" + b.userId())
                        .header("Authorization", bearerHeader(tokenA)))
                .andExpect(status().isOk())
                .andReturn();
        int code = objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
        assertThat(code).isEqualTo(1025);

        // 用户仍存在、会话键仍在（未被删除/未被清理）
        assertThat(userMapper.selectById(b.userId())).isNotNull();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(b.userId()))).isTrue();
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(b.token())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[补测] delete() 禁止删除自己：返回 1022（对照 update/kick 均为 1022 自保）")
    void delete_self_rejected() throws Exception {
        String email = uniqueEmail("del-self");
        String token = registerAndLoginGetToken(email, "abc123");
        long userId = userIdByEmail(email);
        grantAdminByPresetSuperAdmin(userId);

        MvcResult result = mockMvc.perform(delete("/api/users/" + userId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1022);

        // 自己仍在，会话未被清理
        assertThat(userMapper.selectById(userId)).isNotNull();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(userId))).isTrue();
    }
}