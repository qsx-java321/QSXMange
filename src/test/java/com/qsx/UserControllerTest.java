package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理测试：分页、新增、修改、详情、删除
 */
class UserControllerTest extends BaseIntegrationTest {

    // adminToken() 由 BaseIntegrationTest 提供：注册并绑定 ADMIN 超管角色

    private MvcResult createUser(String token, String email, String password, Integer status) throws Exception {
        String statusJson = status == null ? "" : ",\"status\":" + status;
        return mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + statusJson + "}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    // ---------- 分页查询 ----------

    @Test
    @DisplayName("分页查询全部")
    void page_all() throws Exception {
        String token = adminToken();
        // 取基线后断言增量：库中可能已有预置种子用户（admin@qsx.com 等），
        // 断言绝对总数会隐含依赖「库里只有测试用户」这一前提
        long baseline = totalUsers(token);

        createUser(token, uniqueEmail("p1"), "abc123", null);
        createUser(token, uniqueEmail("p2"), "abc123", null);
        createUser(token, uniqueEmail("p3"), "abc123", null);

        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        long total = json.path("data").path("total").asLong();
        assertThat(total).isEqualTo(baseline + 3);
        assertThat(json.path("data").path("records").size()).isEqualTo((int) total);
    }

    /** 当前用户总数（用于相对断言） */
    private long totalUsers(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("total").asLong();
    }

    @Test
    @DisplayName("按邮箱模糊筛选")
    void page_byEmail() throws Exception {
        String token = adminToken();
        String target = uniqueEmail("filter-t");
        createUser(token, target, "abc123", null);
        createUser(token, uniqueEmail("other"), "abc123", null);

        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .param("email", "filter-t"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(json.path("data").path("records").get(0).path("email").asText()).isEqualTo(target);
    }

    @Test
    @DisplayName("按状态筛选")
    void page_byStatus() throws Exception {
        String token = adminToken();
        createUser(token, uniqueEmail("normal"), "abc123", 0);
        createUser(token, uniqueEmail("disabled"), "abc123", 1);

        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("data").path("total").asLong()).isEqualTo(1);
        assertThat(json.path("data").path("records").get(0).path("status").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页可选性")
    void page_pagination() throws Exception {
        String token = adminToken();
        for (int i = 0; i < 5; i++) {
            createUser(token, uniqueEmail("page-" + i), "abc123", null);
        }

        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token))
                        .param("pageNum", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("data").path("pageNum").asLong()).isEqualTo(1);
        assertThat(json.path("data").path("pageSize").asLong()).isEqualTo(2);
        assertThat(json.path("data").path("total").asLong()).isGreaterThan(2);
        assertThat(json.path("data").path("pages").asLong()).isGreaterThan(0);
    }

    // ---------- 新增 ----------

    @Test
    @DisplayName("新增用户成功")
    void create_success() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("create-ok");
        MvcResult result = createUser(token, email, "abc123", 0);

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("新增邮箱重复")
    void create_duplicate() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("create-dup");
        createUser(token, email, "abc123", null);

        MvcResult result = createUser(token, email, "abc123", null);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1001);
    }

    // ---------- 修改 ----------

    @Test
    @DisplayName("修改用户成功")
    void update_success() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("upd-ok");
        MvcResult created = createUser(token, email, "abc123", 0);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        String newEmail = uniqueEmail("upd-new");
        MvcResult result = mockMvc.perform(put("/api/users/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"nickname\":\"改后昵称\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("email").asText()).isEqualTo(newEmail);
        assertThat(json.path("data").path("nickname").asText()).isEqualTo("改后昵称");
        assertThat(json.path("data").path("status").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("修改为自身邮箱可成功")
    void update_sameEmail() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("upd-same");
        MvcResult created = createUser(token, email, "abc123", 0);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult result = mockMvc.perform(put("/api/users/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("修改为他人邮箱被拒")
    void update_otherEmail() throws Exception {
        String token = adminToken();
        String emailA = uniqueEmail("upd-a");
        String emailB = uniqueEmail("upd-b");
        createUser(token, emailA, "abc123", null);
        MvcResult createdB = createUser(token, emailB, "abc123", null);
        long idB = objectMapper.readTree(createdB.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult result = mockMvc.perform(put("/api/users/" + idB)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + emailA + "\",\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1001);
    }

    @Test
    @DisplayName("修改不存在的用户")
    void update_notFound() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(put("/api/users/99999")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@test.com\",\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1004);
    }

    // ---------- 详情 ----------

    @Test
    @DisplayName("查询详情")
    void getById_success() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("detail-ok");
        MvcResult created = createUser(token, email, "abc123", 0);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult result = mockMvc.perform(get("/api/users/" + id)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("查询不存在的详情")
    void getById_notFound() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(get("/api/users/99999")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1004);
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("删除用户后分页不再返回")
    void delete_success() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("del-ok");
        MvcResult created = createUser(token, email, "abc123", 0);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult deleteResult = mockMvc.perform(delete("/api/users/" + id)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode deleteJson = objectMapper.readTree(deleteResult.getResponse().getContentAsString());
        assertThat(deleteJson.path("code").asInt()).isEqualTo(200);

        // 分页查询不应再包含
        MvcResult page = mockMvc.perform(get("/api/users")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pageJson = objectMapper.readTree(page.getResponse().getContentAsString());
        for (JsonNode record : pageJson.path("data").path("records")) {
            assertThat(record.path("email").asText()).isNotEqualTo(email);
        }
    }

    @Test
    @DisplayName("删除不存在的用户")
    void delete_notFound() throws Exception {
        String token = adminToken();
        MvcResult result = mockMvc.perform(delete("/api/users/99999")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1004);
    }

    @Test
    @DisplayName("删除后 id 复用查询/修改/删除均为不存在")
    void delete_thenIdInvisible() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("del-invis");
        MvcResult created = createUser(token, email, "abc123", 0);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(delete("/api/users/" + id).header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/users/" + id)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1004);

        MvcResult update = mockMvc.perform(put("/api/users/" + id)
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@test.com\",\"status\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(update.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1004);

        MvcResult deleteAgain = mockMvc.perform(delete("/api/users/" + id)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(deleteAgain.getResponse().getContentAsString()).path("code").asInt()).isEqualTo(1004);
    }
}