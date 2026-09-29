package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.PermissionConstants;
import com.qsx.domain.entity.RolePermission;
import com.qsx.domain.entity.User;
import com.qsx.mapper.PermissionMapper;
import com.qsx.mapper.RolePermissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜单管理测试：树形结构/CRUD/删除约束/防环/授权可见/关联清理/权限隔离
 */
class MenuTest extends BaseIntegrationTest {

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 测试菜单统一前缀，用例结束后物理清理 */
    private static final String TEST_PREFIX = "test-";

    @AfterEach
    void cleanTestMenus() {
        jdbcTemplate.update("DELETE rp FROM sys_role_permission rp " +
                "JOIN sys_permission p ON rp.permission_id = p.id WHERE p.code LIKE '" + TEST_PREFIX + "%'");
        jdbcTemplate.update("DELETE FROM sys_permission WHERE code LIKE '" + TEST_PREFIX + "%'");
    }

    // ---------- 辅助 ----------

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String uniqueRoleCode() {
        return "test-role-" + uniqueSuffix();
    }

    private MvcResult createMenu(String token, String code, String name, String type, Long parentId, Integer sort)
            throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\"code\":\"").append(code).append("\",\"name\":\"").append(name)
                .append("\",\"type\":\"").append(type).append("\"");
        if (parentId != null) {
            json.append(",\"parentId\":").append(parentId);
        }
        if (sort != null) {
            json.append(",\"sort\":").append(sort);
        }
        json.append("}");
        return mockMvc.perform(post("/api/menus")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long menuId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    /** 取 body 里的业务码 */
    private int bodyCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
    }

    private MvcResult createRole(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"测试角色\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long roleId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void assignPermissions(String token, long roleId, Long... permissionIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < permissionIds.length; i++) {
            if (i > 0) {
                ids.append(",");
            }
            ids.append(permissionIds[i]);
        }
        mockMvc.perform(put("/api/roles/" + roleId + "/permissions")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[" + ids + "]}"))
                .andExpect(status().isOk());
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

    private long userIdByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("超管可获取完整菜单树，预置结构为 系统管理→4子菜单")
    void admin_menu_tree_ok() throws Exception {
        String token = adminToken();
        MvcResult tree = mockMvc.perform(get("/api/menus/tree")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        JsonNode root = objectMapper.readTree(tree.getResponse().getContentAsString());
        assertThat(root.path("code").asInt()).isEqualTo(200);
        JsonNode top = root.path("data");
        assertThat(top.size()).isEqualTo(1);
        assertThat(top.get(0).path("code").asText()).isEqualTo("system");
        assertThat(top.get(0).path("children").size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("普通用户：tree 返回 403，current 返回空数组")
    void normal_user_menu_denied() throws Exception {
        String token = registerAndLoginGetToken(uniqueEmail("menu"), "abc123");
        mockMvc.perform(get("/api/menus/tree").header("Authorization", bearerHeader(token)))
                .andExpect(status().isForbidden());
        MvcResult cur = mockMvc.perform(get("/api/menus/current")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(cur.getResponse().getContentAsString()).path("data").size()).isEqualTo(0);
    }

    @Test
    @DisplayName("菜单新增→修改→删除 全流程")
    void menu_crud() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "crud-" + uniqueSuffix();
        long id = menuId(createMenu(token, code, "测试菜单", "MENU", 0L, 99));

        MvcResult upd = mockMvc.perform(put("/api/menus/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"改名菜单\",\"type\":\"MENU\"," +
                                "\"parentId\":0,\"path\":\"x\",\"component\":\"x\",\"icon\":\"x\",\"visible\":1,\"sort\":88}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(upd.getResponse().getContentAsString())
                .path("data").path("name").asText()).isEqualTo("改名菜单");

        mockMvc.perform(delete("/api/menus/" + id).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        assertThat(permissionMapper.selectById(id)).isNull();
    }

    @Test
    @DisplayName("存在子节点时删除父菜单返回 1014")
    void menu_delete_blocked_when_has_children() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "parent-" + uniqueSuffix();
        long parentId = menuId(createMenu(token, code, "父目录", "MENU", 0L, 1));
        createMenu(token, TEST_PREFIX + "child-" + uniqueSuffix(), "子按钮", "PERMISSION", parentId, 1);

        MvcResult del = mockMvc.perform(delete("/api/menus/" + parentId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(del.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1014);
    }

    @Test
    @DisplayName("父节点指向自身返回 1015（防环）")
    void menu_parent_cycle_rejected() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "cycle-" + uniqueSuffix();
        long id = menuId(createMenu(token, code, "环测试", "MENU", 0L, 1));

        MvcResult upd = mockMvc.perform(put("/api/menus/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"环测试\",\"type\":\"MENU\"," +
                                "\"parentId\":" + id + ",\"visible\":1,\"sort\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(upd.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1015);
    }

    @Test
    @DisplayName("菜单标识重复返回 1016")
    void menu_code_duplicate_rejected() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "dup-" + uniqueSuffix();
        createMenu(token, code, "首次", "MENU", 0L, 1);
        MvcResult dup = createMenu(token, code, "重复", "MENU", 0L, 1);
        assertThat(objectMapper.readTree(dup.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1016);
    }

    @Test
    @DisplayName("逻辑删除后的菜单标识不可复用：再次新增返回 1016 而非 500")
    void menu_code_reuse_after_delete_rejected_1016() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "reuse-" + uniqueSuffix();
        long id = menuId(createMenu(token, code, "待删除", "MENU", 0L, 1));

        mockMvc.perform(delete("/api/menus/" + id).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        // uk_perm_code 是物理唯一索引，逻辑删除行仍占用标识，判重必须包含已删除行
        MvcResult again = createMenu(token, code, "重建", "MENU", 0L, 1);
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1016);
    }

    @Test
    @DisplayName("菜单 type/visible/sort 白名单：非法取值一律 400（原为静默落库）")
    void menu_fieldWhitelist_validated() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "wl-" + uniqueSuffix();
        String createBase = "{\"code\":\"" + code + "\",\"name\":\"白名单测试\"";

        // type 写错大小写：修复前会静默落库，而菜单树按 type == "MENU" 过滤
        // ⇒ 该菜单对所有用户的菜单树静默消失（#39）
        assertThat(bodyCode(postMenuRaw(token, createBase + ",\"type\":\"menu\"}")))
                .as("type 必须走白名单").isEqualTo(400);
        assertThat(bodyCode(postMenuRaw(token, createBase + ",\"visible\":2}"))).isEqualTo(400);
        assertThat(bodyCode(postMenuRaw(token, createBase + ",\"sort\":-1}"))).isEqualTo(400);
        assertThat(bodyCode(postMenuRaw(token, createBase + ",\"sort\":10000}"))).isEqualTo(400);
        // 非法取值不得落库
        assertThat(permissionMapper.selectList(new LambdaQueryWrapper<com.qsx.domain.entity.Permission>()
                .eq(com.qsx.domain.entity.Permission::getCode, code))).isEmpty();

        // 合法取值仍可创建（防校验误伤）
        long id = menuId(createMenu(token, code, "白名单测试", "MENU", 0L, 99));

        // 修改路径同样受约束（MenuUpdateRequest）
        String updateBase = "{\"code\":\"" + code + "\",\"name\":\"白名单测试\"";
        assertThat(bodyCode(putMenuRaw(token, id,
                updateBase + ",\"type\":\"BUTTON\",\"visible\":1,\"sort\":1}"))).isEqualTo(400);
        assertThat(bodyCode(putMenuRaw(token, id,
                updateBase + ",\"type\":\"MENU\",\"visible\":2,\"sort\":1}"))).isEqualTo(400);
        assertThat(bodyCode(putMenuRaw(token, id,
                updateBase + ",\"type\":\"MENU\",\"visible\":1,\"sort\":10000}"))).isEqualTo(400);
        // 校验失败不得产生半成品写入
        assertThat(permissionMapper.selectById(id).getType()).isEqualTo("MENU");
    }

    @Test
    @DisplayName("并发创建相同菜单标识：唯一索引兜底，不得出现未映射的 500")
    void menu_create_concurrent_sameCode_noServerError() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "race-" + uniqueSuffix();
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    MvcResult result = mockMvc.perform(post("/api/menus")
                                    .header("Authorization", bearerHeader(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"code\":\"" + code + "\",\"name\":\"并发菜单\",\"type\":\"MENU\"}"))
                            .andReturn();
                    return bodyCode(result);
                }));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            // 判重与插入之间的并发窗口：唯一索引保证只有一个成功，
            // 其余必须落到业务码 1016，而不是 DuplicateKeyException 直穿的 500
            assertThat(codes).as("并发撞唯一索引必须映射为 1016，而不是 500").doesNotContain(500);
            assertThat(codes).filteredOn(c -> c == 200).as("唯一索引保证只有一个插入成功").hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("权限分页排序补 id tiebreaker：同 sort 的行顺序稳定（不再靠存储引擎的偶然顺序）")
    void permissionPage_sameSort_orderedById() throws Exception {
        String token = adminToken();
        // 三条同 sort 的测试菜单，用 code 前缀过滤后即为完整结果集
        String prefix = TEST_PREFIX + "ord-" + uniqueSuffix();
        long id1 = menuId(createMenu(token, prefix + "-a", "排序A", "MENU", 0L, 8888));
        long id2 = menuId(createMenu(token, prefix + "-b", "排序B", "MENU", 0L, 8888));
        long id3 = menuId(createMenu(token, prefix + "-c", "排序C", "MENU", 0L, 8888));

        JsonNode records = objectMapper.readTree(mockMvc.perform(get("/api/permissions")
                        .header("Authorization", bearerHeader(token))
                        .param("code", prefix)
                        .param("pageSize", "500"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("records");

        assertThat(records.size()).isEqualTo(3);
        List<Long> ids = new ArrayList<>();
        records.forEach(node -> ids.add(node.path("id").asLong()));
        // sort 相同时按 id 升序兜底：只按 sort 排序是非全序，跨页可能重复/漏行（#38）
        assertThat(ids).containsExactly(id1, id2, id3);
    }

    /** 直接 POST 一段菜单创建 JSON（不做状态码断言，由调用方检查业务码） */
    private MvcResult postMenuRaw(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/menus")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 直接 PUT 一段菜单修改 JSON（不做状态码断言，由调用方检查业务码） */
    private MvcResult putMenuRaw(String token, long id, String json) throws Exception {
        return mockMvc.perform(put("/api/menus/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    @DisplayName("授权菜单对应用户可见：建菜单/按钮→绑角色→current 返回该菜单")
    void menu_visible_for_authorized_user() throws Exception {
        String admin = adminToken();
        String menuCode = TEST_PREFIX + "vis-" + uniqueSuffix();
        String permCode = TEST_PREFIX + "btn-" + uniqueSuffix();
        long menuId = menuId(createMenu(admin, menuCode, "可见目录", "MENU", 0L, 1));
        createMenu(admin, permCode, "可见按钮", "PERMISSION", menuId, 1);

        long roleId = roleId(createRole(admin, uniqueRoleCode()));
        assignPermissions(admin, roleId, menuId, permIdByCode(permCode));

        String email = uniqueEmail("mv");
        String userToken = registerAndLoginGetToken(email, "abc123");
        assignRoles(admin, userIdByEmail(email), roleId);

        MvcResult cur = mockMvc.perform(get("/api/menus/current")
                        .header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(cur.getResponse().getContentAsString()).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("code").asText()).isEqualTo(menuCode);
    }

    @Test
    @DisplayName("删除菜单会清理角色-权限关联")
    void menu_delete_cleans_role_permission() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "clean-" + uniqueSuffix();
        long menuId = menuId(createMenu(token, code, "清理测试", "MENU", 0L, 1));
        long roleId = roleId(createRole(token, uniqueRoleCode()));
        assignPermissions(token, roleId, menuId);

        assertThat(rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, menuId))).isEqualTo(1);

        mockMvc.perform(delete("/api/menus/" + menuId).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());
        assertThat(rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, menuId))).isZero();
    }

    @Test
    @DisplayName("修改菜单标识被拒 1023，且原标识与名称未被改动")
    void menu_code_immutable_rejected_1023() throws Exception {
        String token = adminToken();
        String code = TEST_PREFIX + "immutable-" + uniqueSuffix();
        long id = menuId(createMenu(token, code, "原名", "MENU", 0L, 1));

        MvcResult upd = mockMvc.perform(put("/api/menus/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "X\",\"name\":\"试图改标识\",\"type\":\"MENU\"," +
                                "\"parentId\":0,\"visible\":1,\"sort\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(upd.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1023);

        // 标识是 @PreAuthorize 与菜单过滤的依据，拒绝后不能留下任何半成品写入
        assertThat(permissionMapper.selectById(id).getCode()).isEqualTo(code);
        assertThat(permissionMapper.selectById(id).getName()).isEqualTo("原名");
    }

    @Test
    @DisplayName("菜单不存在的修改/删除返回 1013")
    void menu_not_found_1013() throws Exception {
        String token = adminToken();
        long ghost = 99999999L;

        MvcResult upd = mockMvc.perform(put("/api/menus/" + ghost)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ghost\",\"name\":\"ghost\",\"type\":\"MENU\"," +
                                "\"parentId\":0,\"visible\":1,\"sort\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(upd.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1013);

        MvcResult del = mockMvc.perform(delete("/api/menus/" + ghost)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(del.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1013);
    }

    @Test
    @DisplayName("父节点非法返回 1015：不存在 / 非菜单类型 / 指向自己的子孙")
    void menu_parent_invalid_cases_1015() throws Exception {
        String token = adminToken();
        String buttonCode = TEST_PREFIX + "btn-parent-" + uniqueSuffix();
        long buttonId = menuId(createMenu(token, buttonCode, "按钮节点", "PERMISSION", 0L, 1));

        // 1) 父节点是按钮权限（type=PERMISSION），不能作为菜单父级
        MvcResult underButton = createMenu(token, TEST_PREFIX + "bad1-" + uniqueSuffix(), "挂按钮下", "MENU", buttonId, 1);
        assertThat(objectMapper.readTree(underButton.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1015);

        // 2) 父节点不存在
        MvcResult underGhost = createMenu(token, TEST_PREFIX + "bad2-" + uniqueSuffix(), "挂幽灵下", "MENU", 99999999L, 1);
        assertThat(objectMapper.readTree(underGhost.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1015);

        // 3) 把父菜单挂到自己的子孙下（防环的深度场景，区别于「指向自身」）
        String parentCode = TEST_PREFIX + "p-" + uniqueSuffix();
        long parentId = menuId(createMenu(token, parentCode, "父", "MENU", 0L, 1));
        long childId = menuId(createMenu(token, TEST_PREFIX + "c-" + uniqueSuffix(), "子", "MENU", parentId, 1));

        MvcResult cycle = mockMvc.perform(put("/api/menus/" + parentId)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + parentCode + "\",\"name\":\"父\",\"type\":\"MENU\"," +
                                "\"parentId\":" + childId + ",\"visible\":1,\"sort\":1}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(cycle.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1015);
    }

    /** 按权限码查权限ID（用于关联测试数据） */
    private long permIdByCode(String code) {
        return permissionMapper.selectOne(
                new LambdaQueryWrapper<com.qsx.domain.entity.Permission>()
                        .eq(com.qsx.domain.entity.Permission::getCode, code)).getId();
    }

    // ---------- 系统内置行不可删除（1035） ----------

    @Test
    @DisplayName("删除系统内置权限码行被拒 1035，且其角色关联原样保留")
    void builtinPermission_delete_rejected_1035() throws Exception {
        String token = adminToken();
        long permId = permIdByCode(PermissionConstants.ROLE_UPDATE);
        long relationsBefore = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, permId));
        assertThat(relationsBefore).as("前置：内置权限码本就被 ADMIN 绑定").isPositive();

        MvcResult del = mockMvc.perform(delete("/api/menus/" + permId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();

        assertThat(objectMapper.readTree(del.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1035);
        // 机制层：拦截必须发生在「物理清关联」与「逻辑删除」之前——只断言业务码的话，
        // 一个「先清关联再报错」的实现也能骗过测试，而 ADMIN 的授权已被悄悄摘掉一块
        assertThat(permissionMapper.selectById(permId)).as("权限行仍在").isNotNull();
        assertThat(rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, permId)))
                .as("角色-权限关联既不能被清、也不能被改")
                .isEqualTo(relationsBefore);
    }

    @Test
    @DisplayName("删除预置菜单行同样被拒 1035，且优先于「存在子节点 1014」")
    void builtinMenu_delete_rejected_1035() throws Exception {
        String token = adminToken();
        long menuId = permIdByCode(PermissionConstants.MENU_ENTRY_USER);
        long relationsBefore = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, menuId));

        MvcResult del = mockMvc.perform(delete("/api/menus/" + menuId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn();

        // system-user 本身还有一堆 user:* 子节点：若主闸排在子节点检查之后，这里会返回 1014，
        // 操作者会以为"把子节点删掉就能删掉它"，而事实是永远不行
        assertThat(objectMapper.readTree(del.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(1035);
        assertThat(permissionMapper.selectById(menuId)).isNotNull();
        assertThat(rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, menuId)))
                .isEqualTo(relationsBefore);
    }

    @Test
    @DisplayName("机制层：BUILT_IN_CODES 与库中预置数据一一对应（漏登记一个即转红）")
    void builtInCodes_matchPresetData() {
        List<String> presetCodes = permissionMapper
                .selectList(new LambdaQueryWrapper<com.qsx.domain.entity.Permission>())
                .stream()
                .map(com.qsx.domain.entity.Permission::getCode)
                .filter(code -> !code.startsWith(TEST_PREFIX))
                .toList();

        assertThat(presetCodes).as("前置：库中应恰好只有 init.sql 预置的 29 个 code").hasSize(29);
        assertThat(PermissionConstants.BUILT_IN_CODES)
                .as("保护集合必须与预置数据一一对应：漏一个 = 该行可被删且无法经接口重建")
                .containsExactlyInAnyOrderElementsOf(presetCodes);
    }
}
