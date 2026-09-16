package com.qsx;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.config.properties.AuthSessionProperties;
import com.qsx.security.session.AuthRedisKeys;
import com.qsx.security.session.AuthSession;
import com.qsx.security.session.AuthSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话层（Lua）专项测试：直连真实 Redis，不经 HTTP。
 *
 * 三条 Lua 脚本是本次改造全部风险的集中点（原子性、字段读取、TTL 边界、崩溃残留），
 * 这里也是唯一能在早期抓住这些陷阱的地方——Redis 只在该脚本首次 EVAL 时才校验 Lua 语法。
 *
 * 会话层不校验用户是否存在于库，故使用不落库的伪 userId。
 */
@SpringBootTest
class SessionLuaTest {

    private static final Long USER_ID = 900001L;

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AuthSessionProperties properties;

    @Autowired
    @Qualifier("authSessionIssueScript")
    private DefaultRedisScript<String> issueScript;

    @BeforeEach
    @AfterEach
    void cleanSessionKeys() {
        for (String prefix : List.of(AuthRedisKeys.AT_PREFIX,
                AuthRedisKeys.RT_PREFIX,
                AuthRedisKeys.SESSION_PREFIX)) {
            scanAndDelete(prefix + "*");
        }
    }

    // ---------- 签发 ----------

    @Test
    @DisplayName("issue：三键齐全、映射正确、TTL 符合配置")
    void issue_createsThreeKeysWithExpectedTtl() {
        AuthSession session = authSessionService.issue(USER_ID);

        String atKey = AuthRedisKeys.at(session.accessToken());
        String rtKey = AuthRedisKeys.rt(session.refreshToken());
        String sessionKey = AuthRedisKeys.session(USER_ID);

        assertThat(stringRedisTemplate.opsForValue().get(atKey)).isEqualTo(String.valueOf(USER_ID));
        assertThat(stringRedisTemplate.opsForValue().get(rtKey)).isEqualTo(String.valueOf(USER_ID));

        Map<Object, Object> hash = stringRedisTemplate.opsForHash().entries(sessionKey);
        assertThat(hash)
                .containsEntry("accessToken", session.accessToken())
                .containsEntry("refreshToken", session.refreshToken())
                .containsKey("firstLoginTs");

        // at ≈ 30 分钟；rt 与 session ≈ 7 天
        assertThat(ttlOf(atKey))
                .isBetween(properties.atTtlSeconds() - 5, properties.atTtlSeconds());
        assertThat(ttlOf(rtKey))
                .isBetween(properties.rtTtlSeconds() - 5, properties.rtTtlSeconds());
        assertThat(ttlOf(sessionKey))
                .isBetween(properties.rtTtlSeconds() - 5, properties.rtTtlSeconds());
    }

    @Test
    @DisplayName("issue 两次（单端登录）：旧会话的 at/rt 全部失效")
    void issue_twice_overwritesOldSession() {
        AuthSession first = authSessionService.issue(USER_ID);
        AuthSession second = authSessionService.issue(USER_ID);

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(first.accessToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(first.refreshToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(second.accessToken()))).isTrue();
        assertThat(stringRedisTemplate.opsForHash().get(AuthRedisKeys.session(USER_ID), "accessToken"))
                .isEqualTo(second.accessToken());
    }

    // ---------- 轮换 ----------

    @Test
    @DisplayName("rotate：旧 at/rt 立即失效，firstLoginTs 不被改写，session TTL 续期")
    void rotate_issuesNewPairAndInvalidatesOld() {
        AuthSession first = authSessionService.issue(USER_ID);
        String sessionKey = AuthRedisKeys.session(USER_ID);
        Object firstLoginTs = stringRedisTemplate.opsForHash().get(sessionKey, "firstLoginTs");

        AuthSession rotated = authSessionService.rotate(first.refreshToken());

        assertThat(rotated.userId()).isEqualTo(USER_ID);
        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        // 旧令牌立即失效（旧 AT 不能继续用满 30 分钟）
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(first.accessToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(first.refreshToken()))).isFalse();
        // 新令牌可用
        assertThat(stringRedisTemplate.opsForValue().get(AuthRedisKeys.at(rotated.accessToken())))
                .isEqualTo(String.valueOf(USER_ID));
        // 绝对上限的时间基准轮换不重置
        assertThat(stringRedisTemplate.opsForHash().get(sessionKey, "firstLoginTs"))
                .isEqualTo(firstLoginTs);
        assertThat(ttlOf(sessionKey))
                .isBetween(properties.rtTtlSeconds() - 5, properties.rtTtlSeconds());
    }

    @Test
    @DisplayName("rotate：旧 refresh token 重放被拒（严格轮换）")
    void rotate_replayOldRefreshToken_rejected() {
        AuthSession first = authSessionService.issue(USER_ID);
        authSessionService.rotate(first.refreshToken());

        assertRefreshInvalid(() -> authSessionService.rotate(first.refreshToken()));
    }

    @Test
    @DisplayName("rotate：session 记录的 rt 与提交的不一致时被拒（双保险）")
    void rotate_sessionMismatch_rejected() {
        AuthSession session = authSessionService.issue(USER_ID);
        // 篡改 session 记录的 rt：等价于「提交的是已被新登录覆盖的旧 rt」
        stringRedisTemplate.opsForHash().put(AuthRedisKeys.session(USER_ID),
                "refreshToken", "f".repeat(64));

        assertRefreshInvalid(() -> authSessionService.rotate(session.refreshToken()));
    }

    @Test
    @DisplayName("rotate：超过 30 天绝对上限时拒绝并清空会话三键")
    void rotate_exceedsMaxLifetime_expiresAndClearsSession() {
        AuthSession session = authSessionService.issue(USER_ID);
        String sessionKey = AuthRedisKeys.session(USER_ID);
        // 把首次登录时间改到上限之前
        stringRedisTemplate.opsForHash().put(sessionKey, "firstLoginTs",
                String.valueOf(System.currentTimeMillis() - properties.maxLifetimeMillis() - 60_000));

        assertRefreshInvalid(() -> authSessionService.rotate(session.refreshToken()));

        assertThat(stringRedisTemplate.hasKey(sessionKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.accessToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();
    }

    @Test
    @DisplayName("rotate：session 缺 firstLoginTs 时按无效处理并清理（不让 Lua 报错）")
    void rotate_malformedSession_rejected() {
        AuthSession session = authSessionService.issue(USER_ID);
        String sessionKey = AuthRedisKeys.session(USER_ID);
        stringRedisTemplate.opsForHash().delete(sessionKey, "firstLoginTs");

        assertRefreshInvalid(() -> authSessionService.rotate(session.refreshToken()));

        assertThat(stringRedisTemplate.hasKey(sessionKey)).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();
    }

    @Test
    @DisplayName("rotate：并发使用同一 refresh token，恰好一个成功")
    void rotate_concurrent_sameRefreshToken_exactlyOneWins() throws Exception {
        AuthSession session = authSessionService.issue(USER_ID);

        List<Object> results = runConcurrently(
                () -> authSessionService.rotate(session.refreshToken()),
                () -> authSessionService.rotate(session.refreshToken()));

        long succeeded = results.stream().filter(AuthSession.class::isInstance).count();
        long rejected = results.stream()
                .filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(e -> e.getCode() == ResultCode.REFRESH_TOKEN_INVALID.getCode())
                .count();

        assertThat(succeeded).as("并发刷新必须恰好一个成功").isEqualTo(1);
        assertThat(rejected).as("其余请求应收到 1019").isEqualTo(1);
    }

    @Test
    @DisplayName("rotate 与 remove 并发：无论谁先执行完，会话最终必须消失（踢下线不被在途刷新撤销）")
    void concurrentRotateAndRemove_leavesNoSession() throws Exception {
        for (int i = 0; i < 10; i++) {
            AuthSession session = authSessionService.issue(USER_ID);
            String refreshToken = session.refreshToken();

            runConcurrently(
                    () -> authSessionService.rotate(refreshToken),
                    () -> {
                        authSessionService.remove(USER_ID);
                        return null;
                    });

            // 原子化前这里是竞态：remove 若落在轮换的 GET 与 SET 之间，
            // 轮换会把刚被踢掉的会话重新写回来并续上新的 7 天 TTL
            assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(USER_ID)))
                    .as("第 %d 轮：并发踢人后会话不得残留", i + 1)
                    .isFalse();
        }
    }

    // ---------- 清理 ----------

    @Test
    @DisplayName("remove：清空三键，且幂等")
    void remove_clearsAllThreeKeys() {
        AuthSession session = authSessionService.issue(USER_ID);

        authSessionService.remove(USER_ID);

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(USER_ID))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(session.accessToken()))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(session.refreshToken()))).isFalse();
        // 重复清理不报错
        authSessionService.remove(USER_ID);
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(USER_ID))).isFalse();
    }

    @Test
    @DisplayName("remove 之后重放旧 refresh token 被拒，且不会把会话写回来")
    void rotate_afterRemove_rejected() {
        AuthSession session = authSessionService.issue(USER_ID);
        authSessionService.remove(USER_ID);

        assertRefreshInvalid(() -> authSessionService.rotate(session.refreshToken()));
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(USER_ID))).isFalse();
    }

    // ---------- 查找 ----------

    @Test
    @DisplayName("findUserIdByAccessToken：有效返回 userId；未知/畸形/已吊销返回 null")
    void findUserIdByAccessToken_returnsNullForUnknownToken() {
        AuthSession session = authSessionService.issue(USER_ID);
        assertThat(authSessionService.findUserIdByAccessToken(session.accessToken())).isEqualTo(USER_ID);

        assertThat(authSessionService.findUserIdByAccessToken(null)).isNull();
        assertThat(authSessionService.findUserIdByAccessToken("not-a-token")).isNull();
        assertThat(authSessionService.findUserIdByAccessToken("f".repeat(64))).isNull();

        authSessionService.remove(USER_ID);
        assertThat(authSessionService.findUserIdByAccessToken(session.accessToken())).isNull();
    }

    // ---------- Lua 参数守卫 ----------

    @Test
    @DisplayName("Lua 参数守卫：TTL 非法时报错且不写入半成品会话")
    void luaGuardsRejectInvalidArgs() {
        String at = "a".repeat(64);
        String rt = "b".repeat(64);

        // 直接以非法 TTL 调脚本：必须在任何写操作之前被拒（Lua 报错不会回滚已执行的写）
        assertThatThrownBy(() -> stringRedisTemplate.execute(issueScript,
                List.of(AuthRedisKeys.session(USER_ID), AuthRedisKeys.at(at), AuthRedisKeys.rt(rt)),
                String.valueOf(USER_ID), at, rt, "0", "0",
                String.valueOf(System.currentTimeMillis()),
                AuthRedisKeys.AT_PREFIX, AuthRedisKeys.RT_PREFIX))
                .isInstanceOf(RuntimeException.class);

        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.session(USER_ID))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.at(at))).isFalse();
        assertThat(stringRedisTemplate.hasKey(AuthRedisKeys.rt(rt))).isFalse();
    }

    // ---------- 私有辅助 ----------

    private void assertRefreshInvalid(ThrowingCallable callable) {
        assertThatThrownBy(callable::call)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ResultCode.REFRESH_TOKEN_INVALID.getCode()));
    }

    /** 两个任务并发执行，等待都结束后返回结果（异常作为结果返回） */
    private List<Object> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return (Object) task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long ttlOf(String key) {
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl == null ? -1 : ttl;
    }

    private void scanAndDelete(String pattern) {
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(500).build())) {
            Set<String> keys = new HashSet<>();
            cursor.forEachRemaining(keys::add);
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            // Redis 不可用则忽略（其余用例会因此失败并暴露问题）
        }
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call();
    }
}
