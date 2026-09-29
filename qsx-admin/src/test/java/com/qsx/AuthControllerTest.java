package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.domain.entity.User;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证中心测试：注册、登录、当前用户、修改密码
 */
class AuthControllerTest extends BaseIntegrationTest {

    // ---------- 注册 ----------

    @Test
    @DisplayName("注册成功")
    void register_success() throws Exception {
        String email = uniqueEmail("reg-ok");
        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.path("code").asInt()).isEqualTo(200);

        // 库中存在，status=0
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email));
        assertThat(user).isNotNull();
        assertThat(user.getStatus()).isEqualTo(0);
        assertThat(user.getPassword()).startsWith("$2");
    }

    @Test
    @DisplayName("并发窗口兜底：邮箱被逻辑删除行占用唯一索引时，注册返回 1001 而非 500")
    void register_uniqueIndexRace_fallsBackTo1001() throws Exception {
        // 「查重 → 插入」之间存在并发窗口（并发/双击注册）。uk_email 兜住了一致性，
        // 但修复前 DuplicateKeyException 直穿会变成 body 500。
        // 这里用「逻辑删除行仍物理占用 uk_email」构造等价场景：getByEmail 被 @TableLogic
        // 过滤 ⇒ 查重通过，插入必然撞唯一索引。删除接口会给邮箱改写 #deleted_ 后缀释放索引，
        // 因此本场景等价于"存量老数据"（#27 的另一条真实触发路径）
        String email = uniqueEmail("race-reg");
        jdbcTemplate.update("INSERT INTO sys_user (email, password, nickname, status, must_change_password, deleted) "
                + "VALUES (?, ?, ?, 0, 0, 1)", email, "$2a$10$race-placeholder", "占位行");

        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).as("必须映射为业务码，而不是 500").isEqualTo(1001);
        assertThat(userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email))).as("不得新增可见账号").isNull();
    }

    @Test
    @DisplayName("注册邮箱超长：400（而非 500），且不落库")
    void register_emailTooLong_rejectedAs400() throws Exception {
        // 101 字符 = EMAIL_MAX + 1。@Email 只限制「本地部分 ≤64、域名标签 ≤63」而不限总长度
        // （Hibernate Validator 实测可放行 260 字符的地址），所以超长地址靠加长域名构造，
        // 拦它的只有 DTO 上的 @Size；没有它就会一路走到 INSERT 撞 VARCHAR(128)，
        // 以 1406 → 500 收场（详见 docs/question-list #19）
        String email = longEmail(101);
        assertThat(email).hasSize(101);

        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).as("参数校验失败应是 400").isEqualTo(400);
        assertThat(json.path("message").asText()).contains("邮箱长度");
        assertThat(userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email))).isNull();
    }

    @Test
    @DisplayName("注册 60 字符邮箱且昵称留空：昵称按列宽截断，注册成功（历史 500 场景）")
    void register_blankNickname_longEmail_truncatesNickname() throws Exception {
        // 昵称留空时默认取邮箱，而 nickname 列只有 50 字符——直接写整串会撞列宽变 500，
        // 且注册是匿名接口，任何访客都能用长邮箱把接口打成 500
        String email = longEmail(60);
        assertThat(email).hasSize(60);

        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).as("昵称必须截断到列宽，而不是 500").isEqualTo(200);

        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getEmail, email));
        assertThat(user).isNotNull();
        assertThat(user.getNickname()).hasSize(50).isEqualTo(email.substring(0, 50));
    }

    /**
     * 构造总长为 {@code length} 且能通过 {@code @Email} 的邮箱。
     *
     * <p>@Email 的约束是「本地部分 ≤64、每个域名标签 ≤63」，对**总长度没有上限**
     * （实测可放行 260 字符的地址），所以长邮箱只能靠加长域名构造。若把长地址写成
     * "a×92@test.com"，先撞的是「本地部分 ≤64」而报格式错误，测不到总长度约束。
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
    @DisplayName("重复注册被拦截")
    void register_duplicateEmail() throws Exception {
        String email = uniqueEmail("reg-dup");
        register(email, "abc123");
        MvcResult result = register(email, "abc123");

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1001);
    }

    @Test
    @DisplayName("注册验证码错误被拒，且不消耗用户手里那张有效的码")
    void register_wrongCaptcha() throws Exception {
        String email = uniqueEmail("reg-cap-wrong");
        String realCode = registerCaptcha(email);
        // 取一个必然不同于真实码的值（真实码恰好是占位值时换一个，避免概率性断言）
        String wrongCode = realCode.equals(FALLBACK_CAPTCHA) ? "111111" : FALLBACK_CAPTCHA;

        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\",\"captcha\":\""
                                + wrongCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1028);

        // 机制层：一次失败只应累加错误计数，不能把码删掉
        assertThat(stringRedisTemplate.opsForValue()
                .get(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email)))
                .as("校验失败不应消耗验证码")
                .isEqualTo(realCode);

        // 换正确的码仍能注册成功——证明上面那张码确实还活着，而不是"又存了一张同值的"
        MvcResult retry = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\",\"captcha\":\""
                                + realCode + "\"}"))
                .andReturn();
        assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()).path("code").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("注册缺验证码")
    void register_missingCaptcha() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("reg-cap-miss") + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("注册缺邮箱")
    void register_missingEmail() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("注册非法邮箱格式")
    void register_invalidEmail() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"abc\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("注册弱密码（纯字母）")
    void register_weakPassword() throws Exception {
        String email = uniqueEmail("reg-weak");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abcdef\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(400);
    }

    // ---------- 登录 ----------

    @Test
    @DisplayName("登录成功返回 token")
    void login_success() throws Exception {
        String email = uniqueEmail("login-ok");
        register(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("token").asText()).isNotBlank();
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("登录密码错误")
    void login_wrongPassword() throws Exception {
        String email = uniqueEmail("login-wrong");
        register(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1002);
    }

    @Test
    @DisplayName("登录禁用的账号被拒")
    void login_disabledUser() throws Exception {
        // 通过用户管理接口新增一个禁用账号（status=1）
        String disabledEmail = uniqueEmail("disabled");
        String adminToken = adminToken();

        // 新增禁用用户
        MvcResult addResult = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\",\"status\":1}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + disabledEmail + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1003);
    }

    // ---------- 当前用户 ----------

    @Test
    @DisplayName("获取当前登录用户")
    void me_success() throws Exception {
        String email = uniqueEmail("me-ok");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(get("/auth/me")
                        .header("Authorization", bearerHeader(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);
        assertThat(json.path("data").path("email").asText()).isEqualTo(email);
    }

    // ---------- 修改密码 ----------

    @Test
    @DisplayName("修改密码成功，旧密码失效新密码可用")
    void changePassword_success() throws Exception {
        String email = uniqueEmail("cp-ok");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(200);

        // 旧密码登录失败
        MvcResult oldLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode oldJson = objectMapper.readTree(oldLogin.getResponse().getContentAsString());
        assertThat(oldJson.path("code").asInt()).isEqualTo(1002);

        // 新密码登录成功
        MvcResult newLogin = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode newJson = objectMapper.readTree(newLogin.getResponse().getContentAsString());
        assertThat(newJson.path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("修改密码原密码错误")
    void changePassword_wrongOld() throws Exception {
        String email = uniqueEmail("cp-wrong");
        String token = registerAndLoginGetToken(email, "abc123");

        MvcResult result = mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong1\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(1006);
    }

    @Test
    @DisplayName("改密无 token 返回 401")
    void changePassword_noToken() throws Exception {
        mockMvc.perform(post("/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"abc123\",\"newPassword\":\"xyz789\"}"))
                .andExpect(status().isUnauthorized());
    }
}