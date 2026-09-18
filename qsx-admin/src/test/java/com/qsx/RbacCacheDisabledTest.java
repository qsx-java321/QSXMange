package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 权限缓存开关关闭时的降级测试：
 * qsx.rbac-cache.enabled=false 时退化为实时查库，不读写 Redis
 */
@SpringBootTest(properties = "qsx.rbac-cache.enabled=false")
@AutoConfigureMockMvc
class RbacCacheDisabledTest extends BaseIntegrationTest {

    @Test
    void disabled_doesNotWriteRedis() throws Exception {
        String email = uniqueEmail("disabled");
        registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 缓存开关关闭：登录后 Redis 中不应出现权限缓存 key
        assertThat(stringRedisTemplate.hasKey("qsx:auth:perm:" + userId)).isFalse();
    }
}
