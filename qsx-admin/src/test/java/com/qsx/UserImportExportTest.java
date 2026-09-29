package com.qsx;

import com.alibaba.excel.EasyExcel;
import com.qsx.domain.entity.User;
import com.qsx.domain.entity.UserRole;
import com.qsx.web.dto.excel.UserExportRow;
import com.qsx.web.dto.excel.UserImportRow;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel 批量导入导出集成测试
 */
class UserImportExportTest extends BaseIntegrationTest {

    private static final String EXCEL_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DEFAULT_PASSWORD = "qsx123456";

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---------- 模板下载 ----------

    @Test
    void templateDownload_shouldReturnExcelAttachment() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/users/import/template")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", containsString("spreadsheetml")));
    }

    // ---------- 导入 ----------

    @Test
    void importUsers_validRows_shouldPersistWithRoles() throws Exception {
        String token = adminToken();
        // 角色码用自建普通角色，不能用 ADMIN——导入侧已禁止内置超管角色（见下两个用例）
        String roleCode = uniqueRoleCode();
        long roleId = createRole(token, roleCode);
        String email1 = uniqueEmail("imp1");
        String email2 = uniqueEmail("imp2");
        MockMultipartFile file = excelFile(List.of(
                row(email1, "导入用户一", "0", roleCode),
                row(email2, "", "", "")));

        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successCount").value(2));

        // 用户已入库：默认密码 BCrypt、昵称回退邮箱、默认状态正常
        User u1 = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email1));
        assertNotNull(u1);
        assertEquals("导入用户一", u1.getNickname());
        assertEquals(0, u1.getStatus());
        assertTrue(passwordEncoder.matches(DEFAULT_PASSWORD, u1.getPassword()));

        User u2 = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email2));
        assertNotNull(u2);
        assertEquals(email2, u2.getNickname());

        // 角色绑定：仅 email1 绑定了该角色
        List<UserRole> rolesOfU1 = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, u1.getId()));
        assertEquals(1, rolesOfU1.size());
        assertEquals(roleId, rolesOfU1.get(0).getRoleId());
        assertEquals(0, userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, u2.getId())));
    }

    @Test
    void importUsers_adminRoleCode_shouldRejectAll() throws Exception {
        String token = adminToken();
        String badEmail = uniqueEmail("imp-admin");
        String okEmail = uniqueEmail("imp-ok");
        MockMultipartFile file = excelFile(List.of(
                row(badEmail, "试图导入超管", "0", "ADMIN"),
                row(okEmail, "合法行", "0", "")));

        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1017))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.errors.length()").value(1))
                // 行号 = Excel 物理行号（表头为第 1 行，首个数据行是第 2 行）
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("第 2 行")))
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("内置超管角色")));

        // 整批拒绝：含 ADMIN 的行与它后面的合法行都不落库。
        // 注意不要断言"用户 email 不存在 ⇒ 角色关联也不存在"以外的东西——
        // 测试超管自己就带一条 ADMIN 关联，按角色 id 计数会恒假
        assertNull(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, badEmail)));
        assertNull(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, okEmail)));
    }

    @Test
    void importUsers_lowercaseAdminCode_shouldAlsoReject() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("imp-admin-lc");
        MockMultipartFile file = excelFile(List.of(row(email, "小写超管码", "0", "admin")));

        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1017))
                // 大小写变体走的不是「禁止内置超管角色」那条闸，而是落到「角色编码不存在」：
                // roleByCode 的键取自库中原样（ADMIN），而 HashMap 大小写敏感 ⇒ 查不到。
                // 后果同样是整批拒绝，故此处只锁定"进不了库"这一事实。
                // 切勿"顺手"把编码校验改成不区分大小写：取用阶段 roleByCode.get(code) 会返回 null 而 NPE
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("角色编码不存在")))
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("admin")));

        assertNull(userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email)));
    }

    @Test
    void importUsers_anyInvalidRow_shouldRejectAll() throws Exception {
        String token = adminToken();
        String existingEmail = uniqueEmail("exist");
        register(existingEmail, "abc123");

        String badRoleEmail = uniqueEmail("role");
        MockMultipartFile file = excelFile(List.of(
                row(existingEmail, "库内重复邮箱", "0", ""),
                row("bad-email", "格式错误", "0", ""),
                row(badRoleEmail, "角色不存在", "0", "NO_SUCH_ROLE"),
                row(uniqueEmail("ok"), "合法行", "1", "")));

        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1017))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.errors.length()").value(3));

        // 整批拒绝：合法行也未入库
        assertFalse(userMapper.selectCount(
                new LambdaQueryWrapper<User>().like(User::getNickname, "合法行")) > 0);
    }

    @Test
    void importUsers_duplicateEmailInFile_shouldReject() throws Exception {
        String token = adminToken();
        String dupEmail = uniqueEmail("dup");
        MockMultipartFile file = excelFile(List.of(
                row(dupEmail, "用户A", "0", ""),
                row(dupEmail, "用户B", "0", "")));

        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1017))
                .andExpect(jsonPath("$.data.errors[0]").value(containsString("重复")));
    }

    @Test
    void importUsers_blankFile_shouldFail() throws Exception {
        String token = adminToken();
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.xlsx", EXCEL_CONTENT_TYPE, new byte[0]);
        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void importUsers_wrongExtension_shouldFail() throws Exception {
        String token = adminToken();
        MockMultipartFile file = new MockMultipartFile(
                "file", "users.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/users/import")
                        .file(file)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 导出 ----------

    @Test
    void exportUsers_shouldReturnUsersWithRoles() throws Exception {
        String token = adminToken();
        String email = uniqueEmail("exp");
        register(email, "abc123");
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        UserRole relation = new UserRole();
        relation.setUserId(user.getId());
        relation.setRoleId(adminRoleId());
        userRoleMapper.insert(relation);

        MvcResult result = mockMvc.perform(get("/api/users/export")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();

        List<UserExportRow> rows = EasyExcel.read(new ByteArrayInputStream(
                        result.getResponse().getContentAsByteArray()))
                .head(UserExportRow.class)
                .sheet()
                .doReadSync();
        assertTrue(rows.stream().anyMatch(r -> email.equals(r.getEmail())
                && r.getRoleCodes() != null
                && r.getRoleCodes().contains("ADMIN")));
    }

    @Test
    void exportUsers_withFilter_shouldOnlyReturnMatched() throws Exception {
        String token = adminToken();
        String matched = uniqueEmail("match");
        String other = uniqueEmail("other");
        register(matched, "abc123");
        register(other, "abc123");

        MvcResult result = mockMvc.perform(get("/api/users/export")
                        .param("email", matched)
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        List<UserExportRow> rows = EasyExcel.read(new ByteArrayInputStream(
                        result.getResponse().getContentAsByteArray()))
                .head(UserExportRow.class)
                .sheet()
                .doReadSync();
        assertTrue(rows.stream().allMatch(r -> matched.equals(r.getEmail())));
    }

    // ---------- 权限 ----------

    @Test
    void importExport_withoutPermission_shouldReturn403() throws Exception {
        String token = registerAndLoginGetToken(uniqueEmail("norole"), "abc123");
        mockMvc.perform(get("/api/users/export")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/import/template")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isForbidden());
    }

    // ---------- 工具方法 ----------

    /** 与基类清理口径一致的自建角色编码（BaseIntegrationTest 按 test- 前缀物理清理） */
    private String uniqueRoleCode() {
        return "test-role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 经 API 建一个自建角色，返回 roleId */
    private long createRole(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/roles")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"导入测试角色-" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private UserImportRow row(String email, String nickname, String status, String roleCodes) {
        UserImportRow r = new UserImportRow();
        r.setEmail(email);
        r.setNickname(nickname);
        r.setStatus(status);
        r.setRoleCodes(roleCodes);
        return r;
    }

    private MockMultipartFile excelFile(List<UserImportRow> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out)
                .head(UserImportRow.class)
                .sheet("用户导入")
                .doWrite(rows);
        return new MockMultipartFile(
                "file", "users.xlsx", EXCEL_CONTENT_TYPE, out.toByteArray());
    }
}
