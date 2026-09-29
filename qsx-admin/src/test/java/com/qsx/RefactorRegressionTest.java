package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.common.log.AccessLogCommand;
import com.qsx.common.log.AccessLogRecorder;
import com.qsx.domain.entity.User;
import com.qsx.mapper.adapter.AuthUserRepositoryImpl;
import com.qsx.mapper.adapter.UserAuthorityRepositoryImpl;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import com.qsx.security.port.AuthUserRepository;
import com.qsx.security.port.UserAuthorityRepository;
import com.qsx.security.service.SecurityUserDetailsService;
import com.qsx.service.impl.OperationLogServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多模块改造（refactor/multi-module）专项回归测试。
 *
 * <p>普通业务用例无法覆盖以下 6 个点——它们都是「改造后才可能出现、
 * 且失败时不报错或只在启动期才炸」的静默失效，因此单列一类锁定：
 *
 * <ul>
 *   <li>R1 端口装配：适配器漏 @Component 会在启动期抛 NoSuchBeanDefinitionException</li>
 *   <li>R2 SecurityUser 快照：持有 AuthUserAccount 后字段是否等值</li>
 *   <li>R3 /auth/me 时间戳：UserVO.from(AuthUserAccount) 新路径是否丢字段</li>
 *   <li>R4 @Async 代理：record() 若被同类自调用，异步会静默失效</li>
 *   <li>R5 端口委托：适配器是否原样透传 UserMapper 的 SQL 语义</li>
 *   <li>R6 审计取用户：审计记录的操作人是否仍来自 SecurityUser 快照</li>
 * </ul>
 */
class RefactorRegressionTest extends BaseIntegrationTest {

    /** 与 SecurityConfig 白名单一致；本类只读预置超管，不产生测试数据 */
    private static final String SEED_ADMIN_EMAIL = "admin@qsx.com";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AuthUserRepository authUserRepository;

    @Autowired
    private UserAuthorityRepository userAuthorityRepository;

    @Autowired
    private SecurityUserDetailsService securityUserDetailsService;

    // ---------- R1：端口装配完整性 ----------

    @Test
    @DisplayName("R1 三个端口的实现必须来自业务模块的适配器（漏 @Component 会在启动期炸）")
    void ports_areWiredToExpectedImplementations() {
        // 注意：不能直接对 Bean 做 isInstanceOf——@Async 会让容器返回 JDK 动态代理
        //（OperationLogServiceImpl 实现了接口，@EnableAsync 默认 proxyTargetClass=false）。
        // 必须用 AopUtils.getTargetClass() 取穿透代理后的真实类型。
        Class<?> userRepo = AopUtils.getTargetClass(applicationContext.getBean(AuthUserRepository.class));
        assertThat(userRepo).isEqualTo(AuthUserRepositoryImpl.class);
        assertThat(userRepo.getPackageName()).isEqualTo("com.qsx.mapper.adapter");

        Class<?> authorityRepo = AopUtils.getTargetClass(applicationContext.getBean(UserAuthorityRepository.class));
        assertThat(authorityRepo).isEqualTo(UserAuthorityRepositoryImpl.class);
        assertThat(authorityRepo.getPackageName()).isEqualTo("com.qsx.mapper.adapter");

        Class<?> recorder = AopUtils.getTargetClass(applicationContext.getBean(AccessLogRecorder.class));
        assertThat(recorder).isEqualTo(OperationLogServiceImpl.class);
        assertThat(recorder.getPackageName()).isEqualTo("com.qsx.service.impl");
    }

    // ---------- R2：SecurityUser 快照与库中行逐字段一致 ----------

    @Test
    @DisplayName("R2 SecurityUser 持有的 AuthUserAccount 必须与库中该行逐字段一致")
    void securityUserSnapshot_matchesDatabaseRow() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("snap"), "abc123");

        SecurityUser securityUser = securityUserDetailsService.loadUserById(session.userId());
        User dbRow = userMapper.selectById(session.userId());
        assertThat(dbRow).isNotNull();

        AuthUserAccount account = securityUser.getAccount();
        assertThat(account).as("SecurityUser 必须携带认证快照").isNotNull();
        assertThat(account.id()).isEqualTo(dbRow.getId());
        assertThat(account.email()).isEqualTo(dbRow.getEmail());
        assertThat(account.nickname()).isEqualTo(dbRow.getNickname());
        assertThat(account.password()).isEqualTo(dbRow.getPassword());
        assertThat(account.status()).isEqualTo(dbRow.getStatus());
        assertThat(account.createTime()).isEqualTo(dbRow.getCreateTime());
        assertThat(account.updateTime()).isEqualTo(dbRow.getUpdateTime());
        // 本行是**有意补的**：record 加组件不会让逐字段断言自动覆盖它，
        // 漏搬运的表现是「标志恒为 false」——一个不会有任何报错的安全缺口
        assertThat(account.mustChangePassword())
                .isEqualTo(Boolean.TRUE.equals(dbRow.getMustChangePassword()));

        // UserDetails 契约不得因改造而改变
        assertThat(securityUser.getId()).isEqualTo(dbRow.getId());
        assertThat(securityUser.getUsername()).isEqualTo(dbRow.getEmail());
        assertThat(securityUser.isEnabled()).isTrue();
        assertThat(account.enabled()).isTrue();
    }

    // ---------- R3：/auth/me 走新路径后时间戳不能丢 ----------

    @Test
    @DisplayName("R3 /auth/me 的 createTime/updateTime 必须非空（新路径不丢字段）")
    void me_returnsNonEmptyTimestampsViaAccountSnapshot() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("me"), "abc123");

        MvcResult result = mockMvc.perform(get("/auth/me")
                        .header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("id").asLong()).isEqualTo(session.userId());
        assertThat(data.path("email").asText()).isEqualTo(session.email());
        assertThat(data.path("nickname").asText()).isNotBlank();
        assertThat(data.path("status").asInt()).isZero();
        // 关键：UserVO.from(User) 与 UserVO.from(AuthUserAccount) 必须返回同一结构
        assertThat(data.path("createTime").asText()).isNotBlank();
        assertThat(data.path("updateTime").asText()).isNotBlank();
    }

    // ---------- R4：@Async 必须标在 record() 上且 Bean 被代理 ----------

    @Test
    @DisplayName("R4 record() 必须有 @Async 且 Bean 是代理（自调用会让异步静默失效）")
    void accessLogRecorder_isAsyncProxied() throws Exception {
        Object recorder = applicationContext.getBean(AccessLogRecorder.class);
        assertThat(AopUtils.isAopProxy(recorder))
                .as("AccessLogRecorder 未走代理：@Async 会静默失效，日志落库退化为同步")
                .isTrue();

        Method recordMethod = OperationLogServiceImpl.class.getMethod("record", AccessLogCommand.class);
        Async async = AnnotationUtils.findAnnotation(recordMethod, Async.class);
        assertThat(async)
                .as("record() 上缺少 @Async 注解")
                .isNotNull();
        // 线程池 Bean 名定义在 qsx-framework 的 AsyncConfig，写错名字会退化为默认线程池
        assertThat(async.value()).isEqualTo("operationLogExecutor");
    }

    // ---------- R5：端口适配器必须原样透传 SQL 语义 ----------

    @Test
    @DisplayName("R5 两个查询端口必须与 UserMapper 返回完全相同的结果（含 r.status=0 过滤）")
    void authorityRepository_delegatesToUserMapperWithoutChangingSql() {
        // 用预置超管：它有 ADMIN 角色与全量权限，结果非空，能真正比出差异
        User admin = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, SEED_ADMIN_EMAIL));
        assertThat(admin).as("预置超管缺失，请先导入 init.sql").isNotNull();

        assertThat(userAuthorityRepository.selectRoleCodes(admin.getId()))
                .isEqualTo(userMapper.selectRoleCodes(admin.getId()))
                .contains(ADMIN_ROLE_CODE);

        assertThat(userAuthorityRepository.selectPermissionCodes(admin.getId()))
                .isEqualTo(userMapper.selectPermissionCodes(admin.getId()))
                .isNotEmpty();
    }

    // ---------- R6：审计记录的操作人仍来自 SecurityUser 快照 ----------

    @Test
    @DisplayName("R6 审计日志的 userId/username 必须来自 SecurityUser 快照（五处补记之一）")
    void auditLog_capturesUserFromSecurityUserSnapshot() throws Exception {
        LoginSession session = loginGetAuth(uniqueEmail("audit"), "abc123");

        // /auth/me 属 com.qsx.web.controller 包，会被 OperationLogAspect 覆盖
        mockMvc.perform(get("/auth/me")
                        .header("Authorization", bearerHeader(session.token())))
                .andExpect(status().isOk());

        Map<String, Object> row = awaitLatestLogRow("/auth/me", session.userId());
        assertThat(row).as("3 秒内未等到本用例用户的审计日志落库（@Async 可能已失效）").isNotNull();
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(session.userId());
        assertThat(row.get("username")).isEqualTo(session.email());
        assertThat(((Number) row.get("success")).intValue()).isEqualTo(1);
    }

    /**
     * 轮询等待异步落库（不用固定 sleep，避免 flaky 与无谓等待）。
     *
     * <p>必须带上 userId 过滤：库里已有其它用例写下的同名 url 记录，
     * 只按 url 取最新一条会立刻命中陈旧数据，断言就变成对别人的日志下结论。
     */
    private Map<String, Object> awaitLatestLogRow(String url, long userId) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbcTemplate.queryForList(
                    "SELECT user_id, username, success FROM sys_operation_log "
                            + "WHERE url = ? AND user_id = ? ORDER BY id DESC LIMIT 1", url, userId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        return null;
    }
}