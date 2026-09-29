package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.common.constant.CaptchaScene;
import com.qsx.security.session.AuthRedisKeys;
import com.qsx.service.captcha.CaptchaRedisKeys;
import com.qsx.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试基类：复用现有 QSXManager 库，测试前清空 sys_user / sys_user_role，
 * 提供注册、登录取 token、构造超管等公共工具方法。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    /** 内置超管角色编码（init.sql 预置） */
    protected static final String ADMIN_ROLE_CODE = "ADMIN";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserMapper userMapper;

    @Autowired
    protected UserRoleMapper userRoleMapper;

    @Autowired
    protected RoleMapper roleMapper;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @Autowired
    protected UserService userService;

    @Autowired
    protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** RBAC 权限缓存 key 前缀（与 PermissionCacheServiceImpl 保持一致） */
    private static final String RBAC_CACHE_KEY_PREFIX = "qsx:auth:perm:";

    /**
     * 需要在每个用例前后清空的 Redis 前缀清单。
     *
     * 注意这是**显式枚举**而非 `qsx:auth:*` 通配：新增一类键时必须在此登记，
     * 否则会跨用例残留——例如验证码的发送间隔与当日计数残留会让
     * 「重发覆盖」「60s 限流」「错 5 次作废」这类用例随机转红。
     */
    private static final List<String> REDIS_KEY_PREFIXES = List.of(
            // RBAC 权限缓存
            RBAC_CACHE_KEY_PREFIX,
            // 会话三键：access token / refresh token / 会话索引
            AuthRedisKeys.AT_PREFIX,
            AuthRedisKeys.RT_PREFIX,
            AuthRedisKeys.SESSION_PREFIX,
            // 邮箱验证码四键：code / attempt / limit / daily
            CaptchaRedisKeys.PREFIX);

    /** 测试用户邮箱特征（uniqueEmail 生成），用于把测试数据与预置种子数据隔离 */
    private static final String TEST_EMAIL_LIKE = "%@test.com%";

    /**
     * 取不到验证码时的占位值（见 {@link #register}）。
     * 服务端「唯一性先于验证码」，走这条分支的场景都停在唯一性校验上，占位码不会被真正比对。
     */
    protected static final String FALLBACK_CAPTCHA = "000000";

    /**
     * 测试角色编码前缀（各测试类的 uniqueRoleCode 统一使用）。
     *
     * 预置角色只有 ADMIN，不带 test- 前缀；此前各测试类各用一套前缀（ROLE_/test-rbac-role-），
     * 且多数不清理，导致反复运行后 sys_role 里堆积上百条测试角色（唯一索引 uk_role_code
     * 是**物理**唯一，这些残留会一直占用编码空间）。统一前缀后由基类物理清理。
     */
    private static final String TEST_ROLE_CODE_LIKE = "test-%";

    /** 登录会话（token / refreshToken / userId / email） */
    protected record LoginSession(String token, String refreshToken, Long userId, String email) {
    }

    @BeforeEach
    void cleanDatabase() {
        cleanTestUsers();
        cleanTestRoles();
        cleanOperationLog();
        cleanRedisKeys();
    }

    @AfterEach
    void tearDown() {
        cleanTestUsers();
        cleanTestRoles();
        cleanOperationLog();
        cleanRedisKeys();
    }

    /**
     * 清理测试用户（角色/权限/预置超管为常驻种子数据，不清理）。
     *
     * 两点必须注意：
     * 1. 不能用 userMapper.delete(null)——MyBatis-Plus 对带 @TableLogic 的实体
     *    会执行逻辑删除（UPDATE deleted=1），既不隔离用例、又污染预置数据；
     * 2. **只能删测试用户**：早期实现是 `DELETE FROM sys_user` 全表物理删除，
     *    会把预置超管 admin@qsx.com 一并删掉，导致「跑一次 mvn test 就要手工恢复一次 admin」
     *    反复发生。测试用户邮箱统一来自 uniqueEmail()（以 @test.com 结尾），据此隔离；
     *    带尾部通配符是为了覆盖被逻辑删除的测试用户——其邮箱会被改写成
     *    {@code xxx@test.com#deleted_<时间戳>}。
     */
    private void cleanTestUsers() {
        jdbcTemplate.update("DELETE ur FROM sys_user_role ur "
                + "JOIN sys_user u ON ur.user_id = u.id WHERE u.email LIKE ?", TEST_EMAIL_LIKE);
        jdbcTemplate.update("DELETE FROM sys_user WHERE email LIKE ?", TEST_EMAIL_LIKE);
    }

    /**
     * 清理测试角色（预置 ADMIN 不带 test- 前缀，不动）。
     *
     * 用户-角色 / 角色-权限关联必须先物理删除再删角色：角色删除接口是**逻辑删除**，
     * 若只删 sys_role，残留的关联行会指向已删除角色，污染后续用例（如 selectRoleCodes 的 JOIN）。
     */
    private void cleanTestRoles() {
        jdbcTemplate.update("DELETE ur FROM sys_user_role ur "
                + "JOIN sys_role r ON ur.role_id = r.id WHERE r.code LIKE ?", TEST_ROLE_CODE_LIKE);
        jdbcTemplate.update("DELETE rp FROM sys_role_permission rp "
                + "JOIN sys_role r ON rp.role_id = r.id WHERE r.code LIKE ?", TEST_ROLE_CODE_LIKE);
        jdbcTemplate.update("DELETE FROM sys_role WHERE code LIKE ?", TEST_ROLE_CODE_LIKE);
    }

    /**
     * 清理操作日志。
     *
     * 审计切面会把每个进入 Controller 的请求异步落库，一轮全量测试即可累积数百行，
     * 而这些行全是测试噪音（该表非预置数据）。LogTest 自身也在每个用例前清空，
     * 这里提升为基类行为，避免开发库里越堆越多。
     */
    private void cleanOperationLog() {
        jdbcTemplate.update("DELETE FROM sys_operation_log");
    }

    /**
     * 清理 Redis 中的权限缓存、会话三键与验证码键（避免跨用例污染）。
     *
     * 注意：认证链路现已依赖 Redis，Redis 不可用时本方法静默跳过，
     * 但需要登录态的用例会因 401 大批失败并暴露问题。
     */
    private void cleanRedisKeys() {
        try {
            for (String prefix : REDIS_KEY_PREFIXES) {
                scanAndDelete(prefix + "*");
            }
        } catch (Exception e) {
            // Redis 不可用则忽略
        }
    }

    /**
     * 用 scan 而非 keys：keys 是 O(N) 阻塞命令，
     * 而本清理在每个用例前后各执行一次（会话键会随每次登录累积）
     */
    private void scanAndDelete(String pattern) {
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(500).build())) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        }
    }

    /**
     * 生成唯一邮箱，避免跨用例冲突
     */
    protected String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    /**
     * 注册一个用户，密码默认 abc123。
     *
     * <p>注册已要求邮箱验证码，这里走**真实发码链路**：先调 {@code POST /auth/captcha}，
     * 再从 Redis 读出验证码随注册提交。好处是各测试类里几十处 {@code register(...)}
     * 调用点一行都不用改，且注册用例顺带覆盖了发码链路。
     *
     * <p>兜底：邮箱已注册（发码返回 1001）或触发 60 秒限流时拿不到码，此时用占位码提交——
     * 服务端是「唯一性先于验证码」，该场景下注册仍返回 1001，断言语义不受影响。
     */
    protected MvcResult register(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\",\"captcha\":\"" + registerCaptcha(email) + "\"}"))
                .andReturn();
    }

    /**
     * 走真实发码接口取一张注册验证码；取不到时返回 {@link #FALLBACK_CAPTCHA}（见 {@link #register}）。
     *
     * <p>同一用例里对同一邮箱注册两次（如「删除后释放邮箱再注册」）会撞上 60 秒发送间隔，
     * 此时删掉间隔标记重发一次——那正是真实用户等满 60 秒的效果，用例不可能真的去等。
     * 重发仍拿不到码（邮箱已注册 → 1001）才回落到占位码。
     */
    protected String registerCaptcha(String email) throws Exception {
        String code = sendCaptchaAndReadCode(email);
        if (code == null) {
            stringRedisTemplate.delete(CaptchaRedisKeys.limit(CaptchaScene.REGISTER, email));
            code = sendCaptchaAndReadCode(email);
        }
        return code == null ? FALLBACK_CAPTCHA : code;
    }

    private String sendCaptchaAndReadCode(String email) throws Exception {
        mockMvc.perform(post("/auth/captcha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"" + CaptchaScene.REGISTER.name()
                                + "\",\"email\":\"" + email + "\"}"))
                .andReturn();
        return stringRedisTemplate.opsForValue()
                .get(CaptchaRedisKeys.code(CaptchaScene.REGISTER, email));
    }

    /**
     * 注册成功后，用账号密码登录并返回 token
     */
    protected String registerAndLoginGetToken(String email, String password) throws Exception {
        register(email, password);
        return loginGetToken(email, password);
    }

    /**
     * 登录并返回 token
     */
    protected String loginGetToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    /**
     * 注册并登录，返回完整会话（token / refreshToken / userId / email）。
     * 内部先注册再登录（重复注册返回 1001 属幂等场景，不影响后续登录）。
     */
    protected LoginSession loginGetAuth(String email, String password) throws Exception {
        register(email, password);
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new LoginSession(data.path("token").asText(),
                data.path("refreshToken").asText(),
                data.path("userId").asLong(),
                data.path("email").asText());
    }

    /**
     * 通用：POST 一个 JSON body，返回 MvcResult（不校验状态码）
     */
    protected MvcResult postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /**
     * 生成带 Bearer 前缀的请求头
     */
    protected String bearerHeader(String token) {
        return "Bearer " + token;
    }

    /**
     * 查询内置超管角色的ID（init.sql 预置，测试期常驻）
     */
    protected long adminRoleId() throws Exception {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, ADMIN_ROLE_CODE));
        if (role == null) {
            throw new IllegalStateException("未找到内置角色 " + ADMIN_ROLE_CODE + "，请先导入新版 init.sql");
        }
        return role.getId();
    }

    /**
     * 构造一个绑定 ADMIN 超管角色的用户并返回其 token
     * （走真实业务链路授权，触发权限缓存失效，保证该用户权限即时生效）
     */
    protected String adminToken() throws Exception {
        String email = uniqueEmail("admin");
        String token = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
        grantAdminByPresetSuperAdmin(userId);
        return token;
    }

    /** 预置超管（init.sql 种子数据）——闸 2 之后，授予 ADMIN 只能由它发起 */
    protected static final String PRESET_ADMIN_EMAIL = "admin@qsx.com";
    protected static final String PRESET_ADMIN_PASSWORD = "admin123";

    /** 预置超管的 token（走真实登录）；造超管与"授权类"用例都会用到 */
    protected String presetAdminToken() throws Exception {
        return loginGetToken(PRESET_ADMIN_EMAIL, PRESET_ADMIN_PASSWORD);
    }

    /**
     * 由**预置超管**经 HTTP 把 ADMIN 角色授给目标用户。
     *
     * <p>闸 2（{@code ADMIN_GRANT_REQUIRES_ADMIN}）要求「授予 ADMIN 的操作者本身持有 ADMIN」，
     * 因此测试不能再走「自注册 → 直调 service 自绑 ADMIN」那条老路（既没有操作者上下文，
     * 也不符合闸 2 的语义）。改为与真实运维路径一致：预置超管登录 → HTTP 授权新用户。
     *
     * <p>前置：预置超管未被禁用、ADMIN 角色为启用态（{@code AdminProtectionTest} 会在用例后复位）。
     * 密码取自 init.sql 种子（测试报告记录为 {@code admin@qsx.com / admin123}）。
     */
    protected void grantAdminByPresetSuperAdmin(Long userId) throws Exception {
        String presetToken = presetAdminToken();
        MvcResult result = mockMvc.perform(put("/api/users/" + userId + "/roles")
                        .header("Authorization", bearerHeader(presetToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + adminRoleId() + "]}"))
                .andExpect(status().isOk())
                .andReturn();
        int code = objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asInt();
        if (code != 200) {
            throw new IllegalStateException("预置超管授权 ADMIN 失败（检查 ADMIN 角色是否被停用、"
                    + "预置超管是否被改动）, userId=" + userId + ", code=" + code);
        }
    }
}