package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
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

    /**
     * 清理本类构造的长邮箱用户。
     *
     * <p>长邮箱的形态是 {@code a×64@b×N.test.com}——**不匹配基类清理用的
     * {@code email LIKE '%@test.com%'}` 模式**（域名里多插了一段标签，"{@code @test.com}" 不再相邻），
     * 因此不会被自动清理。漏了这一步会跨用例残留，表现为下次建号莫名返回 1001。
     * 删除改写后的形态（{@code 原邮箱 + #deleted_时间戳}）同样要清。
     */
    @AfterEach
    void cleanLongEmailRows() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE email = ? OR email LIKE ?",
                longEmail(100), longEmail(100) + "#deleted_%");
    }

    /**
     * 构造总长为 {@code length} 且能通过 {@code @Email} 的邮箱。
     *
     * <p>@Email 的约束是「本地部分 ≤64、每个域名标签 ≤63」，对**总长度没有上限**
     * （实测可放行 260 字符的地址），所以长邮箱只能靠加长域名构造。若写成 "d×91@test.com"，
     * 先撞的是「本地部分 ≤64」而报格式错误，测不到总长度约束。
     */
    private static String longEmail(int length) {
        String domain = "test.com";
        int localLen = Math.min(64, length - domain.length() - 1);
        int padding = length - localLen - 1 - domain.length();
        assertThat(localLen).as("总长至少要能容纳 1 位本地部分 + @ + 域名").isPositive();
        assertThat(padding).as("余量应为域名标签长度，不应为负").isNotNegative();
        String domainFull = padding == 0 ? domain : "b".repeat(padding - 1) + "." + domain;
        String email = "a".repeat(localLen) + "@" + domainFull;
        assertThat(email).as("构造结果长度必须与请求一致").hasSize(length);
        return email;
    }

    @Test
    @DisplayName("并发窗口兜底：邮箱被逻辑删除行占用唯一索引时，建号返回 1001 而非 500")
    void createUser_uniqueIndexRace_fallsBackTo1001() throws Exception {
        // 与注册同源的「查重 → 插入」窗口（#27）：用「逻辑删除行仍物理占用 uk_email」构造，
        // getByEmail 被 @TableLogic 过滤 ⇒ 查重通过、插入撞唯一索引；修复前是 body 500
        String token = adminToken();
        String email = uniqueEmail("race-create");
        jdbcTemplate.update("INSERT INTO sys_user (email, password, nickname, status, must_change_password, deleted) "
                + "VALUES (?, ?, ?, 0, 0, 1)", email, "$2a$10$race-placeholder", "占位行");

        MvcResult result = createUser(token, email, "abc123", null);

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).as("必须映射为业务码，而不是 500").isEqualTo(1001);
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
    @DisplayName("新增用户传非法 status（非 0/1）被拒")
    void create_invalidStatus_rejected() throws Exception {
        String token = adminToken();
        // 系统按「status != 0 即禁用」判定，放任其它取值会造出「建好就登不进、也没人知道为什么」的账号
        MvcResult result = createUser(token, uniqueEmail("create-bad"), "abc123", 2);

        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(400);
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
    @DisplayName("删除邮箱长度达上限(100)的用户：邮箱可被改写释放，删除成功并可再次使用")
    void delete_maxLengthEmail_succeeds() throws Exception {
        String token = adminToken();
        // 100 字符 = UserConstants.EMAIL_MAX，是接口允许的最长邮箱。
        // 删除要把邮箱改写成「原邮箱 + #deleted_ + 13 位毫秒」（共 22 字符）来释放唯一索引，
        // 上限若取得过大（如最初文档里写的 110）就会在这一步撞 VARCHAR(128) → 500，
        // 且该账号永远删不掉（历史缺陷）
        String email = longEmail(100);
        assertThat(email).hasSize(100);
        MvcResult created = createUser(token, email, "abc123", 0);
        assertThat(objectMapper.readTree(created.getResponse().getContentAsString())
                .path("code").asInt()).as("前置：上限长度的邮箱必须能建号").isEqualTo(200);
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult deleteResult = mockMvc.perform(delete("/api/users/" + id)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(deleteResult.getResponse().getContentAsString())
                .path("code").asInt()).isEqualTo(200);

        // 机制层：直查库看改写结果（@TableLogic 会过滤掉已删行，必须绕开 mapper）
        String rewritten = jdbcTemplate.queryForObject(
                "SELECT email FROM sys_user WHERE id = ?", String.class, id);
        assertThat(rewritten).startsWith(email).contains("#deleted_");
        assertThat(rewritten.length()).as("改写后的邮箱必须仍放得进 VARCHAR(128)").isLessThanOrEqualTo(128);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = ?", Integer.class, id)).isEqualTo(1);

        // 邮箱已被释放：同一地址可再次建号（这正是改写后缀的目的）
        MvcResult again = createUser(token, email, "abc123", 0);
        assertThat(objectMapper.readTree(again.getResponse().getContentAsString())
                .path("code").asInt()).isEqualTo(200);
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