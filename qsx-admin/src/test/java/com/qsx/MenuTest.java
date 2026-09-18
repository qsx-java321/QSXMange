package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.UUID;

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

    /** 按权限码查权限ID（用于关联测试数据） */
    private long permIdByCode(String code) {
        return permissionMapper.selectOne(
                new LambdaQueryWrapper<com.qsx.domain.entity.Permission>()
                        .eq(com.qsx.domain.entity.Permission::getCode, code)).getId();
    }
}
