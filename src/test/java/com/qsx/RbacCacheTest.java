package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 权限缓存（Redis）专项测试
 * - 登录后回填缓存（含空权限用户，防穿透）
 * - 权限变更后缓存失效、权限即时生效
 * - 删除角色/删除权限/修改权限标识后缓存立即失效（关联行先被清除，反查必须前置在删除之前）
 */
class RbacCacheTest extends BaseIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "qsx:auth:perm:";

    /** 测试数据统一前缀，用例结束后物理清理 */
    private static final String TEST_PERM_PREFIX = "test-rbac-perm-";
    private static final String TEST_ROLE_PREFIX = "test-rbac-role-";

    private String cacheKey(Long userId) {
        return CACHE_KEY_PREFIX + userId;
    }

    @AfterEach
    void cleanTestRbacData() {
        jdbcTemplate.update("DELETE rp FROM sys_role_permission rp " +
                "JOIN sys_permission p ON rp.permission_id = p.id WHERE p.code LIKE '" + TEST_PERM_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM sys_permission WHERE code LIKE '" + TEST_PERM_PREFIX + "%'");
        jdbcTemplate.update("DELETE ur FROM sys_user_role ur " +
                "JOIN sys_role r ON ur.role_id = r.id WHERE r.code LIKE '" + TEST_ROLE_PREFIX + "%'");
        jdbcTemplate.update("DELETE rp FROM sys_role_permission rp " +
                "JOIN sys_role r ON rp.role_id = r.id WHERE r.code LIKE '" + TEST_ROLE_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM sys_role WHERE code LIKE '" + TEST_ROLE_PREFIX + "%'");
    }

    // ---------- 辅助 ----------

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long createPermissionNode(String adminToken, String code) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/menus")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"缓存测试权限\",\"type\":\"PERMISSION\"," +
                                "\"parentId\":0,\"sort\":99}"))
                .andExpect(status().isOk()).andReturn();
        return dataOf(res).path("id").asLong();
    }

    private long createRole(String adminToken, String code) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"缓存测试角色\"}"))
                .andExpect(status().isOk()).andReturn();
        return dataOf(res).path("id").asLong();
    }

    private void assignPermissionToRole(String adminToken, long roleId, long permissionId) throws Exception {
        mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[" + permissionId + "]}"))
                .andExpect(status().isOk());
    }

    private void assignRoleToUser(String adminToken, long userId, long roleId) throws Exception {
        mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + roleId + "]}"))
                .andExpect(status().isOk());
    }

    /**
     * 用一次带 token 的请求触发缓存回填，并读回缓存中的权限码集合
     */
    private List<String> reloadCachedPermissions(String userToken, Long userId) throws Exception {
        mockMvc.perform(get("/auth/me").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());
        String json = stringRedisTemplate.opsForValue().get(cacheKey(userId));
        assertThat(json).as("权限缓存应已回填").isNotNull();
        List<String> codes = new ArrayList<>();
        objectMapper.readTree(json).path("permissions").forEach(node -> codes.add(node.asText()));
        return codes;
    }

    /** 测试夹具：用户 → 测试角色 → 测试权限 的持有链路 */
    private record Holder(String adminToken, String userToken, Long userId,
                          long roleId, String roleCode, long permissionId, String permissionCode) {
    }

    private Holder prepareHolder(String permissionCode) throws Exception {
        String adminToken = adminToken();
        String email = uniqueEmail("cache");
        String userToken = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        String roleCode = TEST_ROLE_PREFIX + uniqueSuffix();
        long roleId = createRole(adminToken, roleCode);
        long permissionId = createPermissionNode(adminToken, permissionCode);
        assignPermissionToRole(adminToken, roleId, permissionId);
        assignRoleToUser(adminToken, userId, roleId);
        return new Holder(adminToken, userToken, userId, roleId, roleCode, permissionId, permissionCode);
    }

    // ---------- 缓存回填与即时生效 ----------

    @Test
    void login_backfillsCacheEvenForEmptyPermissions() throws Exception {
        // 注册并登录一个无任何角色的普通用户
        String email = uniqueEmail("cache");
        registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 登录（认证链 loadUserByUsername）已回填缓存
        assertThat(stringRedisTemplate.hasKey(cacheKey(userId))).isTrue();

        // 空权限也回填（防穿透）：roles / permissions 均为空数组
        String json = stringRedisTemplate.opsForValue().get(cacheKey(userId));
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.path("roles").isArray()).isTrue();
        assertThat(node.path("permissions").isArray()).isTrue();
        assertThat(node.path("roles").isEmpty()).isTrue();
        assertThat(node.path("permissions").isEmpty()).isTrue();
    }

    @Test
    void assignRoles_takesEffectImmediatelyAfterCacheEvict() throws Exception {
        // 普通用户：注册登录，此时无任何权限
        String email = uniqueEmail("rbac");
        String userToken = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 1. 无权限：访问需 user:page 的接口返回 403
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());

        // 2. 超管给该用户分配 ADMIN 角色（走接口 → 事务提交后缓存失效）
        String adminTok = adminToken();
        mockMvc.perform(put("/api/users/{id}/roles", userId)
                        .header("Authorization", bearerHeader(adminTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roleIds", List.of(adminRoleId())))))
                .andExpect(status().isOk());

        // 3. 原 token 再次访问：缓存已失效 → 回源拿到 ADMIN 权限 → 200
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());
    }

    // ---------- 失效链路的回归用例 ----------

    @Test
    @DisplayName("删除角色：持有者缓存立即失效（sys_user_role 先被清除，反查须前置）")
    void deleteRole_evictsHoldersCache() throws Exception {
        String permissionCode = TEST_PERM_PREFIX + uniqueSuffix();
        Holder holder = prepareHolder(permissionCode);

        // 预热并确认缓存中确实含该权限码（否则后续断言可能因前提不成立而空过）
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).contains(permissionCode);

        mockMvc.perform(delete("/api/roles/" + holder.roleId())
                        .header("Authorization", bearerHeader(holder.adminToken())))
                .andExpect(status().isOk());

        // 缓存必须已被删除：旧实现在此处回查 sys_user_role 得到空集，key 残留至 TTL 到期
        assertThat(stringRedisTemplate.hasKey(cacheKey(holder.userId()))).isFalse();
        // 回源后该权限码应消失
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).doesNotContain(permissionCode);
    }

    @Test
    @DisplayName("删除权限：持有者缓存立即失效（sys_role_permission 先被清除，反查须前置）")
    void deletePermission_evictsHoldersCache() throws Exception {
        String permissionCode = TEST_PERM_PREFIX + uniqueSuffix();
        Holder holder = prepareHolder(permissionCode);

        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).contains(permissionCode);

        mockMvc.perform(delete("/api/menus/" + holder.permissionId())
                        .header("Authorization", bearerHeader(holder.adminToken())))
                .andExpect(status().isOk());

        // 缓存必须已被删除：旧实现在此处反查 sys_role_permission 得到空集，被删权限码仍可通过 @PreAuthorize
        assertThat(stringRedisTemplate.hasKey(cacheKey(holder.userId()))).isFalse();
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).doesNotContain(permissionCode);
    }

    @Test
    @DisplayName("修改权限标识被拒：code 创建后不可变（改名会让 @PreAuthorize 全线失配）")
    void updatePermissionCode_rejected() throws Exception {
        String code = TEST_PERM_PREFIX + uniqueSuffix();
        Holder holder = prepareHolder(code);

        MvcResult result = mockMvc.perform(put("/api/menus/" + holder.permissionId())
                        .header("Authorization", bearerHeader(holder.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + TEST_PERM_PREFIX + uniqueSuffix() + "\",\"name\":\"试图改名\"," +
                                "\"type\":\"PERMISSION\",\"parentId\":0,\"visible\":1,\"sort\":99}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1023);

        // 标识未变，持有者权限不受影响
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).contains(code);
    }

    @Test
    @DisplayName("停用角色：持有者立即失去该角色带来的权限（权限查询过滤 status）")
    void disableRole_revokesPermissions() throws Exception {
        String permissionCode = TEST_PERM_PREFIX + uniqueSuffix();
        Holder holder = prepareHolder(permissionCode);
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId())).contains(permissionCode);

        // 停用角色（code 不可变，只改 status）
        MvcResult result = mockMvc.perform(put("/api/roles/" + holder.roleId())
                        .header("Authorization", bearerHeader(holder.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + holder.roleCode() + "\",\"name\":\"缓存测试角色\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);

        // 停用即不再授予权限：回源查询必须过滤 sys_role.status，
        // 否则「停用」只清缓存、结果不变，管理端的停用形同虚设
        assertThat(reloadCachedPermissions(holder.userToken(), holder.userId()))
                .doesNotContain(permissionCode);
    }
}
