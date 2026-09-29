package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.Permission;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.RolePermission;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.mapper.PermissionMapper;
import com.qsx.mapper.RolePermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理测试：分页/列表/详情/CRUD/编码不可变/内置超管保护/删除级联/权限整表替换/授权即时生效。
 *
 * 测试角色统一使用 test- 前缀，由 {@link BaseIntegrationTest} 在每个用例前后物理清理。
 */
class RoleTest extends BaseIntegrationTest {

    /** RBAC 权限缓存 key 前缀（与 PermissionCacheServiceImpl 一致） */
    private static final String PERM_CACHE_PREFIX = "qsx:auth:perm:";

    /** 确认不存在的权限ID（用于 1012 校验） */
    private static final long UNKNOWN_PERMISSION_ID = 99999999L;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    // ---------- 辅助 ----------

    private String uniqueRoleCode() {
        return "test-role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int codeOf(MvcResult result) throws Exception {
        return body(result).path("code").asInt();
    }

    private MvcResult createRole(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"测试角色\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long roleIdOf(MvcResult result) throws Exception {
        return body(result).path("data").path("id").asLong();
    }

    /** 建角色并返回其ID */
    private long newRole(String token, String code) throws Exception {
        return roleIdOf(createRole(token, code));
    }

    private MvcResult updateRole(String token, long roleId, String body) throws Exception {
        return mockMvc.perform(put("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult assignPermissions(String token, long roleId, Long... permissionIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < permissionIds.length; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append(permissionIds[i]);
        }
        return mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[" + ids + "]}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long permissionCount(long roleId) {
        Long c = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        return c == null ? 0 : c;
    }

    private long userRoleCount(long roleId) {
        Long c = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, roleId));
        return c == null ? 0 : c;
    }

    private long permIdByCode(String code) {
        Permission p = permissionMapper.selectOne(
                new LambdaQueryWrapper<Permission>().eq(Permission::getCode, code));
        if (p == null) {
            throw new IllegalStateException("未找到权限码 " + code + "，请先导入新版 init.sql");
        }
        return p.getId();
    }

    private long userIdByEmail(String email) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        return user.getId();
    }

    private void assignRoles(String token, long userId, long... roleIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < roleIds.length; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append(roleIds[i]);
        }
        mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
    }

    private Integer roleStatus(long roleId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sys_role WHERE id = ?", Integer.class, roleId);
    }

    private Integer roleDeletedFlag(long roleId) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM sys_role WHERE id = ?", Integer.class, roleId);
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("角色分页：code/name 模糊 + status 精确 + 分页字段完整")
    void page_filters_and_pagination() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        long enabledId = newRole(token, code);
        long disabledId = newRole(token, code + "-off");
        updateRole(token, disabledId, "{\"code\":\"" + code + "-off\",\"name\":\"停用角色\",\"status\":1}");

        // code 模糊：命中两条
        JsonNode byCode = body(mockMvc.perform(get("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .param("code", code))
                .andExpect(status().isOk()).andReturn());
        assertThat(byCode.path("code").asInt()).isEqualTo(200);
        assertThat(byCode.path("data").path("total").asLong()).isEqualTo(2);
        assertThat(byCode.path("data").path("pageNum").asInt()).isEqualTo(1);
        assertThat(byCode.path("data").path("pageSize").asInt()).isEqualTo(10);

        // name 模糊 + status 精确：只剩停用那条
        JsonNode byStatus = body(mockMvc.perform(get("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .param("code", code).param("name", "停用").param("status", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byStatus.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(byStatus.path("data").path("records").get(0).path("id").asLong()).isEqualTo(disabledId);

        // pageSize=1 分页生效
        JsonNode paged = body(mockMvc.perform(get("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .param("code", code).param("pageNum", "1").param("pageSize", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(paged.path("data").path("total").asLong()).isEqualTo(2);
        assertThat(paged.path("data").path("records").size()).isEqualTo(1);
        // 列表按 id 倒序，后建的排前面
        assertThat(paged.path("data").path("records").get(0).path("id").asLong()).isEqualTo(disabledId);

        // status=0 精确：只剩启用那条
        JsonNode enabledOnly = body(mockMvc.perform(get("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .param("code", code).param("status", "0"))
                .andExpect(status().isOk()).andReturn());
        assertThat(enabledOnly.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(enabledOnly.path("data").path("records").get(0).path("code").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("角色列表 /all 仅返回启用角色")
    void listAll_returns_only_enabled() throws Exception {
        String token = adminToken();
        String enabledCode = uniqueRoleCode();
        String disabledCode = uniqueRoleCode();
        newRole(token, enabledCode);
        long disabledId = newRole(token, disabledCode);
        updateRole(token, disabledId, "{\"code\":\"" + disabledCode + "\",\"name\":\"停用角色\",\"status\":1}");

        JsonNode json = body(mockMvc.perform(get("/api/roles/all")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.path("code").asInt()).isEqualTo(200);

        String codes = json.path("data").toString();
        assertThat(codes).contains(enabledCode).contains(ADMIN_ROLE_CODE).doesNotContain(disabledCode);
        json.path("data").forEach(node -> assertThat(node.path("status").asInt()).isZero());
    }

    @Test
    @DisplayName("角色详情返回已绑定权限ID列表")
    void detail_contains_permission_ids() throws Exception {
        String token = adminToken();
        long roleId = newRole(token, uniqueRoleCode());
        long permA = permIdByCode("role:page");
        long permB = permIdByCode("user:page");
        assignPermissions(token, roleId, permA, permB);

        JsonNode json = body(mockMvc.perform(get("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("code").asText()).startsWith("test-role-");
        assertThat(json.path("data").path("permissionIds").size()).isEqualTo(2);
        assertThat(json.path("data").path("permissionIds").toString())
                .contains(String.valueOf(permA)).contains(String.valueOf(permB));
    }

    @Test
    @DisplayName("角色不存在的四个入口统一返回 1009")
    void not_found_matrix_1009() throws Exception {
        String token = adminToken();
        long ghost = 99999999L;

        assertThat(codeOf(mockMvc.perform(get("/api/roles/" + ghost)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(1009);

        assertThat(codeOf(updateRole(token, ghost,
                "{\"code\":\"ghost\",\"name\":\"ghost\",\"status\":0}"))).isEqualTo(1009);

        assertThat(codeOf(mockMvc.perform(delete("/api/roles/" + ghost)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(1009);

        assertThat(codeOf(assignPermissions(token, ghost, permIdByCode("role:page")))).isEqualTo(1009);
    }

    // ---------- 新增 ----------

    @Test
    @DisplayName("新增角色：编码重复返回 1010，缺字段返回 400")
    void create_duplicate_and_validation() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        newRole(token, code);

        assertThat(codeOf(createRole(token, code))).isEqualTo(1010);

        // code 为空
        assertThat(codeOf(mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"缺少编码\"}"))
                .andExpect(status().isOk()).andReturn())).isEqualTo(400);

        // name 为空
        assertThat(codeOf(mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + uniqueRoleCode() + "\"}"))
                .andExpect(status().isOk()).andReturn())).isEqualTo(400);

        // status 非 0/1
        assertThat(codeOf(mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + uniqueRoleCode() + "\",\"name\":\"x\",\"status\":2}"))
                .andExpect(status().isOk()).andReturn())).isEqualTo(400);
    }

    @Test
    @DisplayName("新增角色默认启用（status 缺省为 0）")
    void create_defaults_to_enabled() throws Exception {
        String token = adminToken();
        long roleId = newRole(token, uniqueRoleCode());
        assertThat(roleStatus(roleId)).isZero();
    }

    // ---------- 修改 ----------

    @Test
    @DisplayName("修改角色成功；修改编码被拒 1024 且名称未被改动")
    void update_ok_and_code_immutable() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        long roleId = newRole(token, code);

        JsonNode ok = body(updateRole(token, roleId,
                "{\"code\":\"" + code + "\",\"name\":\"改名后\",\"description\":\"新描述\",\"status\":1}"));
        assertThat(ok.path("code").asInt()).isEqualTo(200);
        assertThat(ok.path("data").path("name").asText()).isEqualTo("改名后");
        assertThat(roleStatus(roleId)).isEqualTo(1);

        // 编码不可改：命中 1024，且不产生任何写入
        JsonNode rejected = body(updateRole(token, roleId,
                "{\"code\":\"" + code + "X\",\"name\":\"试图改编码\",\"description\":\"d\",\"status\":0}"));
        assertThat(rejected.path("code").asInt()).isEqualTo(1024);

        Role after = roleMapper.selectById(roleId);
        assertThat(after.getCode()).isEqualTo(code);
        assertThat(after.getName()).isEqualTo("改名后");
        assertThat(after.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("内置超管角色不可停用（1026），但可正常重新启用")
    void admin_role_cannot_be_disabled() throws Exception {
        String token = adminToken();
        long adminId = adminRoleId();
        Role preset = roleMapper.selectById(adminId);

        JsonNode rejected = body(updateRole(token, adminId,
                "{\"code\":\"ADMIN\",\"name\":\"" + preset.getName()
                        + "\",\"description\":\"" + preset.getDescription() + "\",\"status\":1}"));
        assertThat(rejected.path("code").asInt()).isEqualTo(1026);
        assertThat(roleStatus(adminId)).isZero();

        // 已启用的 ADMIN 再次提交 status=0 属于幂等启用，应放行
        JsonNode ok = body(updateRole(token, adminId,
                "{\"code\":\"ADMIN\",\"name\":\"" + preset.getName()
                        + "\",\"description\":\"" + preset.getDescription() + "\",\"status\":0}"));
        assertThat(ok.path("code").asInt()).isEqualTo(200);
        assertThat(roleStatus(adminId)).isZero();
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("内置超管角色不可删除（1011），角色保持启用")
    void admin_role_cannot_be_deleted() throws Exception {
        String token = adminToken();
        long adminId = adminRoleId();

        assertThat(codeOf(mockMvc.perform(delete("/api/roles/" + adminId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(1011);

        assertThat(roleDeletedFlag(adminId)).isZero();
        assertThat(roleStatus(adminId)).isZero();
    }

    @Test
    @DisplayName("删除角色：清空角色-权限与用户-角色关联，角色逻辑删除")
    void delete_cascades_and_logical_delete() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        long roleId = newRole(token, code);
        assignPermissions(token, roleId, permIdByCode("role:page"));

        String email = uniqueEmail("roledel");
        registerAndLoginGetToken(email, "abc123");
        assignRoles(token, userIdByEmail(email), roleId);
        assertThat(permissionCount(roleId)).isEqualTo(1);
        assertThat(userRoleCount(roleId)).isEqualTo(1);

        assertThat(codeOf(mockMvc.perform(delete("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(200);

        assertThat(permissionCount(roleId)).isZero();
        assertThat(userRoleCount(roleId)).isZero();
        assertThat(roleDeletedFlag(roleId)).isEqualTo(1);
        assertThat(codeOf(mockMvc.perform(get("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(1009);
    }

    @Test
    @DisplayName("逻辑删除后的角色编码不可复用：再次新增返回 1010 而非 500")
    void recreate_with_logically_deleted_code_rejected() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        long roleId = newRole(token, code);

        mockMvc.perform(delete("/api/roles/" + roleId).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        assertThat(roleDeletedFlag(roleId)).isEqualTo(1);

        // role 的 code 是物理唯一索引（uk_role_code 不含 deleted），
        // 逻辑删除行仍占用编码；唯一性校验必须把它算进去，否则 INSERT 撞唯一索引直接 500
        MvcResult again = createRole(token, code);
        assertThat(codeOf(again)).isEqualTo(1010);
    }

    // ---------- 分配权限 ----------

    @Test
    @DisplayName("分配权限为整表替换：先两条→一条→清空")
    void assign_permissions_replaces_whole_set() throws Exception {
        String token = adminToken();
        long roleId = newRole(token, uniqueRoleCode());
        long permA = permIdByCode("role:page");
        long permB = permIdByCode("user:page");

        assignPermissions(token, roleId, permA, permB);
        assertThat(permissionCount(roleId)).isEqualTo(2);

        assignPermissions(token, roleId, permB);
        assertThat(permissionCount(roleId)).isEqualTo(1);
        assertThat(body(mockMvc.perform(get("/api/roles/" + roleId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())
                .path("data").path("permissionIds").toString()).contains(String.valueOf(permB));

        assignPermissions(token, roleId);
        assertThat(permissionCount(roleId)).isZero();
    }

    @Test
    @DisplayName("分配不存在的权限返回 1012，且原有绑定不被破坏")
    void assign_unknown_permission_keeps_existing() throws Exception {
        String token = adminToken();
        long roleId = newRole(token, uniqueRoleCode());
        long permA = permIdByCode("role:page");
        assignPermissions(token, roleId, permA);

        assertThat(codeOf(assignPermissions(token, roleId, permA, UNKNOWN_PERMISSION_ID))).isEqualTo(1012);
        // 校验必须发生在「先删旧」之前，否则失败一次就把已有授权清空了
        assertThat(permissionCount(roleId)).isEqualTo(1);
    }

    // ---------- 内置超管角色的权限绑定不可修改（1036） ----------

    @Test
    @DisplayName("改内置超管角色的权限被拒 1036：清空与重存都拒绝，且绑定与恢复能力原样保留")
    void admin_role_permissions_immutable_1036() throws Exception {
        String token = adminToken();
        long adminRoleId = adminRoleId();
        long before = permissionCount(adminRoleId);
        assertThat(before).as("前置：内置超管角色本就被绑定全量权限").isPositive();

        // 清空：连恢复所需的 role:assign 一起消失，系统只能改库——本闸要封的主路径
        assertThat(codeOf(assignPermissions(token, adminRoleId))).isEqualTo(1036);
        // 幂等重存（传一份合法子集）同样拒绝：判定只看目标角色身份，不看请求内容
        assertThat(codeOf(assignPermissions(token, adminRoleId, permIdByCode("role:page")))).isEqualTo(1036);

        // 机制层：拦截必须发生在整表替换之前——只断言业务码的话，
        // 一个「先删旧关联再报错」的实现也能骗过测试，而超管已被架空
        assertThat(permissionCount(adminRoleId)).isEqualTo(before);
        assertThat(body(mockMvc.perform(get("/api/roles/" + adminRoleId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())
                .path("data").path("permissionIds").toString())
                .as("恢复能力（role:assign）必须仍在超管角色的绑定里")
                .contains(String.valueOf(permIdByCode("role:assign")));
    }

    @Test
    @DisplayName("机制层：对内置超管角色的权限请求被拒后不发布缓存失效（不产生无谓的全站抖动）")
    void admin_role_permissions_immutable_noCacheEvict() throws Exception {
        String operator = adminToken();
        // 造一个持有 ADMIN 的用户并预热其权限缓存
        LoginSession superAdmin = loginGetAuth(uniqueEmail("warm"), "abc123");
        grantAdminByPresetSuperAdmin(superAdmin.userId());
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(superAdmin.token())))
                .andExpect(status().isOk());
        String cacheKey = PERM_CACHE_PREFIX + superAdmin.userId();
        assertThat(stringRedisTemplate.hasKey(cacheKey)).as("前置：权限缓存已按持有 ADMIN 回填").isTrue();
        long before = permissionCount(adminRoleId());

        // 传的是合法权限 id，唯一的拒绝理由就是「目标是内置超管角色」
        assertThat(codeOf(assignPermissions(operator, adminRoleId(), permIdByCode("role:page"))))
                .isEqualTo(1036);

        assertThat(stringRedisTemplate.hasKey(cacheKey))
                .as("闸在 publishEvent 之前：被拒的请求不该让全站超管的缓存白失效一次")
                .isTrue();
        assertThat(permissionCount(adminRoleId())).isEqualTo(before);
    }

    // ---------- 授权即时生效 ----------
    @Test
    @DisplayName("停用角色后其用户权限立即失效（缓存被精确清除）")
    void disable_role_revokes_permission_immediately() throws Exception {
        String token = adminToken();
        String code = uniqueRoleCode();
        long roleId = newRole(token, code);
        assignPermissions(token, roleId, permIdByCode("user:page"));

        String email = uniqueEmail("roledisable");
        String userToken = registerAndLoginGetToken(email, "abc123");
        long userId = userIdByEmail(email);
        assignRoles(token, userId, roleId);

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());
        String cacheKey = PERM_CACHE_PREFIX + userId;
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue();

        updateRole(token, roleId, "{\"code\":\"" + code + "\",\"name\":\"测试角色\",\"status\":1}");

        // 机制层断言必须放在「发下一个请求」之前：403 请求本身会触发权限缓存回填，
        // 若放在请求之后断言，读到的是刚刚回填的新键（值为空权限），断言恒为 true
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("删除角色后其用户权限立即失效（缓存被精确清除）")
    void delete_role_revokes_permission_immediately() throws Exception {
        String token = adminToken();
        long roleId = newRole(token, uniqueRoleCode());
        assignPermissions(token, roleId, permIdByCode("user:page"));

        String email = uniqueEmail("roledrop");
        String userToken = registerAndLoginGetToken(email, "abc123");
        long userId = userIdByEmail(email);
        assignRoles(token, userId, roleId);

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());
        String cacheKey = PERM_CACHE_PREFIX + userId;
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isTrue();

        mockMvc.perform(delete("/api/roles/" + roleId).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        // 同 disable 用例：先断机制层（缓存键已删除），再发请求看行为
        assertThat(stringRedisTemplate.hasKey(cacheKey)).isFalse();

        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }

    // ---------- 权限隔离 ----------

    @Test
    @DisplayName("普通用户访问角色管理全部接口返回 403")
    void normal_user_forbidden_403() throws Exception {
        String userToken = registerAndLoginGetToken(uniqueEmail("roleplain"), "abc123");

        mockMvc.perform(get("/api/roles").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/roles/all").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/roles/1").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-role-x\",\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/roles/1")
                        .header("Authorization", bearerHeader(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ADMIN\",\"name\":\"x\",\"status\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/roles/1/permissions")
                        .header("Authorization", bearerHeader(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/roles/1").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }
}