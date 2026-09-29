package com.qsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.domain.entity.User;
import com.qsx.security.session.AuthRedisKeys;
import com.qsx.service.captcha.CaptchaRedisKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 强制首次改密的机制层用例（docs/question-list #4）。
 *
 * <p>被测的是「初始口令必须失效」这条能力，断言因此一律打到机制层：
 * 除了业务码，还要直查 {@code sys_user.must_change_password} 与 Redis 的会话键——
 * 只看响应码的话，"标志没清掉"实现照样能写出绿色的用例。
 *
 * <p>三个置 1 落库点里，本类覆盖「后台建号」与「自注册不置 1」两条；
 * 「Excel 导入」那条在 {@code UserImportExportTest} 里（那里已有现成的 Excel 夹具）。
 */
class MustChangePasswordTest extends BaseIntegrationTest {

    /** 后台建号时管理员设定的初始口令——对管理员而言是已知口令，故该账号必须强制改密 */
    private static final String INITIAL_PASSWORD = "abc123";

    /** 改密后的口令（同样满足「字母+数字，6-32 位」） */
    private static final String NEW_PASSWORD = "newPass123";

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 后台建号账号：{@code session} 里的 token 是**受限**的，只能访问 /auth/** */
    private record PendingAccount(Long userId, String email, LoginSession session) {
    }

    private record ApiResponse(int httpStatus, int bodyCode, JsonNode data) {
    }

    private ApiResponse call(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return new ApiResponse(result.getResponse().getStatus(),
                root.path("code").asInt(), root.path("data"));
    }

    /** 经 {@code POST /api/users} 造一个「必须首次改密」的账号 */
    private PendingAccount createByAdmin(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        MvcResult created = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearerHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + INITIAL_PASSWORD + "\"}"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        long userId = call(created).data().path("id").asLong();
        // 用真实登录取受限 token（登录本身**不**被闸拦，否则用户没有会话就改不了密）
        return new PendingAccount(userId, email, loginOnlyGetAuth(email, INITIAL_PASSWORD));
    }

    private ApiResponse getWithToken(String url, String token) throws Exception {
        return call(mockMvc.perform(get(url).header("Authorization", bearerHeader(token))).andReturn());
    }

    private int dbFlag(Long userId) {
        Integer flag = jdbcTemplate.queryForObject(
                "SELECT must_change_password FROM sys_user WHERE id = ?", Integer.class, userId);
        return flag == null ? -1 : flag;
    }

    // ---------- 落库点 ----------

    @Test
    @DisplayName("后台建号（user:create）落库即置 must_change_password=1")
    void adminCreatedUser_isFlagged() throws Exception {
        assertThat(dbFlag(createByAdmin("mcp-create").userId()))
                .as("管理员设定的初始口令对管理员是已知的，与导入同性质")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("自注册**不**置标志：用户自选口令，与'他人已知'不是一回事")
    void registeredUser_isNotFlagged() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("mcp-reg"), "abc123");
        assertThat(dbFlag(session.userId())).isEqualTo(0);

        // 反向锁定：注册账号访问受保护接口应是 403（无 user:page 权限）而不是 1037，
        // 否则说明闸被扩大到了不该扩的账号上
        assertThat(getWithToken("/api/users", session.token()).bodyCode())
                .isEqualTo(403);
    }

    // ---------- 登录与响应契约 ----------

    @Test
    @DisplayName("登录响应回传 mustChangePassword=true（登录本身不被拦，否则用户无法改密）")
    void login_reportsFlag() throws Exception {
        PendingAccount pending = createByAdmin("mcp-login");
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + pending.email() + "\",\"password\":\"" + INITIAL_PASSWORD + "\"}"))
                .andReturn();
        ApiResponse response = call(result);
        assertThat(response.bodyCode()).isEqualTo(200);
        assertThat(response.data().path("mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("/auth/me 与 GET /api/users/{id} 对同一行回传同一标志（两个 UserVO.from 重载同构）")
    void meAndUserDetail_agreeOnFlag() throws Exception {
        PendingAccount pending = createByAdmin("mcp-vo");

        // /auth/me 走 UserVO.from(AuthUserAccount)（豁免放行）
        ApiResponse me = getWithToken("/auth/me", pending.session().token());
        assertThat(me.bodyCode()).isEqualTo(200);
        assertThat(me.data().path("mustChangePassword").asBoolean()).isTrue();

        // 用户详情走 UserVO.from(User)（管理端接口，用超管令牌）
        ApiResponse detail = getWithToken("/api/users/" + pending.userId(), adminToken());
        assertThat(detail.bodyCode()).isEqualTo(200);
        assertThat(detail.data().path("mustChangePassword").asBoolean())
                .as("两条 from 重载必须同构——漏改一条只会让这里静默变成 false/null")
                .isTrue();
    }

    // ---------- 闸 ----------

    @Test
    @DisplayName("受限令牌访问非 /auth 接口：HTTP 200 + body 业务码 1037")
    void gate_blocksNonAuthEndpoint() throws Exception {
        PendingAccount pending = createByAdmin("mcp-gate");
        ApiResponse response = getWithToken("/api/users", pending.session().token());

        // HTTP 200 是刻意约定：这是业务状态而非鉴权失败，与「业务失败一律 HTTP 200 + body 业务码」
        // 一致；403 继续只表示「已登录但无权限」。写这个断言就是在固定该约定
        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.bodyCode()).isEqualTo(1037);
    }

    @Test
    @DisplayName("闸只拦业务接口，不拦 /auth/**：me / refresh / logout 全部放行")
    void gate_exemptsAuthModule() throws Exception {
        PendingAccount pending = createByAdmin("mcp-exempt");
        String token = pending.session().token();

        assertThat(getWithToken("/auth/me", token).bodyCode())
                .as("/auth/me 既要放行，又依赖过滤器填 SecurityContext（故不能用 shouldNotFilter 实现豁免）")
                .isEqualTo(200);

        ApiResponse refreshed = call(mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + pending.session().refreshToken() + "\"}"))
                .andReturn());
        assertThat(refreshed.bodyCode())
                .as("刷新必须放行且必须成功：AT 过期后拿不到新令牌的话，密码就永远改不了")
                .isEqualTo(200);
        assertThat(refreshed.data().path("mustChangePassword").asBoolean())
                .as("刷新响应也回传标志，前端不必先撞一次 1037 才知道要跳改密页")
                .isTrue();

        ApiResponse logout = call(mockMvc.perform(post("/auth/logout")
                        .header("Authorization", bearerHeader(refreshed.data().path("token").asText())))
                .andReturn());
        assertThat(logout.bodyCode()).as("必须能登出，否则受限会话无法退出").isEqualTo(200);
    }

    @Test
    @DisplayName("被闸拦下的请求要落一条审计（httpStatus=200 / success=0 / errorMsg 为 1037 文案）")
    void gate_writesAccessLog() throws Exception {
        PendingAccount pending = createByAdmin("mcp-audit");
        assertThat(getWithToken("/api/users", pending.session().token()).bodyCode()).isEqualTo(1037);

        // 审计是 @Async 落库，轮询等待（与 LogTest 的等待方式一致）
        Integer rows = null;
        for (int i = 0; i < 50 && (rows == null || rows == 0); i++) {
            Thread.sleep(100);
            rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_operation_log WHERE user_id = ? AND http_status = 200 AND success = 0",
                    Integer.class, pending.userId());
        }
        assertThat(rows)
                .as("请求进不了 Controller，切面覆盖不到，必须在过滤器里补记（与 401/403/400 三处补记同族）")
                .isNotNull().isPositive();
        String errorMsg = jdbcTemplate.queryForObject(
                "SELECT error_msg FROM sys_operation_log WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, pending.userId());
        assertThat(errorMsg).contains("初始密码");
    }

    // ---------- 解除 ----------

    @Test
    @DisplayName("通道 A（原密码）改密后：标志置 0 + 会话三键已清 + 新令牌畅通")
    void changePasswordByOldPassword_clearsFlag() throws Exception {
        PendingAccount pending = createByAdmin("mcp-chg");
        String oldAt = pending.session().token();

        ApiResponse changed = call(mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(oldAt))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + INITIAL_PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn());
        assertThat(changed.bodyCode()).isEqualTo(200);

        // 机制层：标志已清（改密与忘记密码重置共用唯一写库入口 updatePassword）
        assertThat(dbFlag(pending.userId())).isEqualTo(0);
        // 机制层：会话已销毁——改密后旧令牌必须立即失效（既有语义，别被这次改动带坏）
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(oldAt))).isFalse();

        // 重新登录必须拿到**畅通**的令牌——只看"标志已清"是不够的：
        // 若 filter 的闸读的是别处的陈旧值，这条才会红
        LoginSession fresh = loginOnlyGetAuth(pending.email(), NEW_PASSWORD);
        ApiResponse afterChange = getWithToken("/api/users", fresh.token());
        assertThat(afterChange.bodyCode())
                .as("改密后应恢复正常的鉴权语义（无 user:page 权限 → 403），而不是继续 1037")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("通道 B（邮箱验证码）改密同样解除标志")
    void changePasswordByCaptcha_clearsFlag() throws Exception {
        PendingAccount pending = createByAdmin("mcp-cap");
        String token = pending.session().token();

        // 发码接口是匿名放行的，但改密场景要求登录态——受限 token 必须能调它，否则通道 B 在
        // 强制改密期间不可用（这正是白名单必须覆盖整个 /auth 的原因之一）
        ApiResponse sent = call(mockMvc.perform(post("/auth/captcha")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + CaptchaScene.CHANGE_PASSWORD.name()
                                + "\",\"email\":\"" + pending.email() + "\"}"))
                .andReturn());
        assertThat(sent.bodyCode()).isEqualTo(200);
        String captcha = stringRedisTemplate.opsForValue()
                .get(CaptchaRedisKeys.code(CaptchaScene.CHANGE_PASSWORD, pending.email()));
        assertThat(captcha).as("发码后 Redis 中应有码").isNotBlank();

        ApiResponse changed = call(mockMvc.perform(post("/auth/change-password")
                        .header("Authorization", bearerHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captcha\":\"" + captcha + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn());
        assertThat(changed.bodyCode()).isEqualTo(200);
        assertThat(dbFlag(pending.userId())).isEqualTo(0);
    }

    @Test
    @DisplayName("忘记密码（匿名重置）同样解除标志——它是'临时口令忘了'的唯一出路")
    void forgotPassword_clearsFlag() throws Exception {
        PendingAccount pending = createByAdmin("mcp-forgot");

        mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + CaptchaScene.FORGOT_PASSWORD.name()
                                + "\",\"email\":\"" + pending.email() + "\"}"))
                .andReturn();
        String captcha = stringRedisTemplate.opsForValue()
                .get(CaptchaRedisKeys.code(CaptchaScene.FORGOT_PASSWORD, pending.email()));
        assertThat(captcha).isNotBlank();

        ApiResponse reset = call(mockMvc.perform(post("/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + pending.email() + "\",\"captcha\":\"" + captcha
                                + "\",\"newPassword\":\"" + NEW_PASSWORD + "\",\"confirmPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn());
        assertThat(reset.bodyCode()).isEqualTo(200);
        assertThat(dbFlag(pending.userId()))
                .as("重置后用户拿到的是只有自己知道的口令，没有理由再强制改密")
                .isEqualTo(0);
    }

    // ---------- 测试底座夹具 ----------

    @Test
    @DisplayName("预置超管夹具自洽：复位用的 hash 必须对应 PRESET_ADMIN_PASSWORD")
    void presetAdminFixture_isSelfConsistent() {
        // 这两个常量是手工同步的一对。失配时的表现是「预置超管登录 1002」，
        // 然后经 adminToken() 扇出的 15 个测试类以同一条 IllegalStateException 集体报错，
        // 看起来像"预置数据坏了"——这条断言把定位成本从「翻 15 个类」压到「一行」
        assertThat(passwordEncoder.matches(PRESET_ADMIN_PASSWORD, PRESET_ADMIN_PASSWORD_HASH))
                .as("改了 hash 常量却忘了同步 PRESET_ADMIN_PASSWORD（或反之）")
                .isTrue();
    }

    // ---------- 数据库映射 ----------

    @Test
    @DisplayName("User 实体确实从库中读回该列（映射漏改会让上面所有机制静默失效）")
    void entity_mapsFlagFromDatabase() throws Exception {
        PendingAccount pending = createByAdmin("mcp-map");
        User row = userMapper.selectById(pending.userId());
        assertThat(row.getMustChangePassword()).isTrue();

        jdbcTemplate.update("UPDATE sys_user SET must_change_password = 0 WHERE id = ?", pending.userId());
        assertThat(userMapper.selectById(pending.userId()).getMustChangePassword()).isFalse();
    }
}
