package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限管理测试（只读三接口）：分页筛选 / 全量列表 / 详情 / 不存在 1012 / 鉴权隔离。
 *
 * 权限码维护走 sql/init.sql，接口只读——因此这里不测写路径，
 * 但必须锁定「预置 23 个权限码 + 6 个菜单」这一契约，否则漏导 init.sql 会静默少权限。
 */
class PermissionTest extends BaseIntegrationTest {

    /** 预置权限码数量（与 PermissionConstants 一致） */
    private static final int PRESET_PERMISSION_CODE_COUNT = 23;

    /** 预置菜单数量（系统管理 + 5 个子菜单，type=MENU） */
    private static final int PRESET_MENU_COUNT = 6;

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int codeOf(MvcResult result) throws Exception {
        return body(result).path("code").asInt();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return body(result).path("data");
    }

    @Test
    @DisplayName("权限列表：按 type 区分菜单与权限码，预置数量与常量一一对应")
    void listAll_covers_preset_contract() throws Exception {
        String token = adminToken();
        JsonNode data = dataOf(mockMvc.perform(get("/api/permissions/all")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn());

        assertThat(data.size()).isEqualTo(PRESET_PERMISSION_CODE_COUNT + PRESET_MENU_COUNT);

        long menuCount = 0;
        long permissionCount = 0;
        for (JsonNode node : data) {
            if ("MENU".equals(node.path("type").asText())) {
                menuCount++;
            } else if ("PERMISSION".equals(node.path("type").asText())) {
                permissionCount++;
            }
        }
        assertThat(menuCount).isEqualTo(PRESET_MENU_COUNT);
        assertThat(permissionCount).isEqualTo(PRESET_PERMISSION_CODE_COUNT);

        // 排序契约：按 sort 升序（前端分配权限面板依赖该顺序）
        int previousSort = Integer.MIN_VALUE;
        for (JsonNode node : data) {
            int sort = node.path("sort").asInt();
            assertThat(sort).isGreaterThanOrEqualTo(previousSort);
            previousSort = sort;
        }
    }

    @Test
    @DisplayName("权限分页：code/name 模糊筛选 + 分页字段完整")
    void page_filters_by_code_and_name() throws Exception {
        String token = adminToken();

        JsonNode byCode = body(mockMvc.perform(get("/api/permissions")
                        .header("Authorization", bearerHeader(token))
                        .param("code", "role:"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byCode.path("code").asInt()).isEqualTo(200);
        assertThat(byCode.path("data").path("total").asLong()).isGreaterThanOrEqualTo(6);
        assertThat(byCode.path("data").path("pageNum").asInt()).isEqualTo(1);
        assertThat(byCode.path("data").path("pageSize").asInt()).isEqualTo(10);
        byCode.path("data").path("records")
                .forEach(node -> assertThat(node.path("code").asText()).startsWith("role:"));

        // 名称模糊：预置「用户管理」菜单下的权限码名称含「用户」
        JsonNode byName = body(mockMvc.perform(get("/api/permissions")
                        .header("Authorization", bearerHeader(token))
                        .param("name", "用户"))
                .andExpect(status().isOk()).andReturn());
        assertThat(byName.path("data").path("total").asLong()).isGreaterThanOrEqualTo(1);

        // 无匹配条件
        JsonNode empty = body(mockMvc.perform(get("/api/permissions")
                        .header("Authorization", bearerHeader(token))
                        .param("code", "no-such-permission-code"))
                .andExpect(status().isOk()).andReturn());
        assertThat(empty.path("data").path("total").asLong()).isZero();
        assertThat(empty.path("data").path("records").size()).isZero();

        // 分页生效
        JsonNode paged = body(mockMvc.perform(get("/api/permissions")
                        .header("Authorization", bearerHeader(token))
                        .param("pageNum", "1").param("pageSize", "3"))
                .andExpect(status().isOk()).andReturn());
        assertThat(paged.path("data").path("total").asLong())
                .isEqualTo(PRESET_PERMISSION_CODE_COUNT + PRESET_MENU_COUNT);
        assertThat(paged.path("data").path("records").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("权限详情返回完整字段；不存在的ID返回 1012")
    void detail_ok_and_not_found() throws Exception {
        String token = adminToken();
        JsonNode all = dataOf(mockMvc.perform(get("/api/permissions/all")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn());
        long someId = all.get(0).path("id").asLong();

        JsonNode detail = dataOf(mockMvc.perform(get("/api/permissions/" + someId)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.path("id").asLong()).isEqualTo(someId);
        assertThat(detail.path("code").asText()).isNotEmpty();
        assertThat(detail.path("type").asText()).isIn("MENU", "PERMISSION");

        assertThat(codeOf(mockMvc.perform(get("/api/permissions/99999999")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk()).andReturn())).isEqualTo(1012);
    }

    @Test
    @DisplayName("未登录访问权限接口返回 401")
    void unauthorized_401() throws Exception {
        mockMvc.perform(get("/api/permissions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/permissions/all")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/permissions/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("普通用户访问权限接口返回 403")
    void normal_user_forbidden_403() throws Exception {
        String userToken = registerAndLoginGetToken(uniqueEmail("permplain"), "abc123");

        mockMvc.perform(get("/api/permissions").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/permissions/all").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/permissions/1").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());
    }
}