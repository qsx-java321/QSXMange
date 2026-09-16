package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.domain.entity.Role;
import com.qsx.domain.entity.User;
import com.qsx.mapper.RoleMapper;
import com.qsx.mapper.UserMapper;
import com.qsx.mapper.UserRoleMapper;
import com.qsx.security.session.AuthRedisKeys;
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

    /** 会话三键前缀（与 AuthRedisKeys 保持一致）：access token / refresh token / 会话索引 */
    private static final List<String> SESSION_KEY_PREFIXES = List.of(
            AuthRedisKeys.AT_PREFIX,
            AuthRedisKeys.RT_PREFIX,
            AuthRedisKeys.SESSION_PREFIX);

    /** 登录会话（token / refreshToken / userId / email） */
    protected record LoginSession(String token, String refreshToken, Long userId, String email) {
    }

    @BeforeEach
    void cleanDatabase() {
        // 物理清空用户与用户-角色关联，保证用例隔离（角色/权限为常驻种子数据，不清理）。
        // 注意：不能用 userMapper.delete(null)——MyBatis-Plus 对带 @TableLogic 的实体
        // 会执行逻辑删除（UPDATE deleted=1），既不隔离用例、又污染预置数据。
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        cleanPermissionCache();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        cleanPermissionCache();
    }

    /**
     * 清理 RBAC 权限缓存与会话三键（避免跨用例污染）。
     *
     * 注意：认证链路现已依赖 Redis，Redis 不可用时本方法静默跳过，
     * 但需要登录态的用例会因 401 大批失败并暴露问题。
     */
    private void cleanPermissionCache() {
        try {
            scanAndDelete(RBAC_CACHE_KEY_PREFIX + "*");
            for (String prefix : SESSION_KEY_PREFIXES) {
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
     * 注册一个用户，密码默认 abc123
     */
    protected MvcResult register(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
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
     * （走真实业务链路 assignRoles，触发权限缓存失效，保证该用户权限即时生效）
     */
    protected String adminToken() throws Exception {
        String email = uniqueEmail("admin");
        String token = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();
        userService.assignRoles(userId, List.of(adminRoleId()));
        return token;
    }
}