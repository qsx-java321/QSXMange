package com.qsx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qsx.domain.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 权限缓存（Redis）专项测试
 * - 登录后回填缓存（含空权限用户，防穿透）
 * - 权限变更后缓存失效、权限即时生效
 */
class RbacCacheTest extends BaseIntegrationTest {

    private static final String CACHE_KEY_PREFIX = "qsx:auth:perm:";

    private String cacheKey(Long userId) {
        return CACHE_KEY_PREFIX + userId;
    }

    @Test
    void login_backfillsCacheEvenForEmptyPermissions() throws Exception {
        // 注册并登录一个无任何角色的普通用户
        String email = uniqueEmail("cache");
        registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 登录（认证链 loadUserByUsername）已回填缓存
        assertThat(stringRedisTemplate.hasKey(cacheKey(userId))).isTrue();

        // 空权限也回填（防穿透）：roles / permissions 均为空数组
        String json = stringRedisTemplate.opsForValue().get(cacheKey(userId));
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.path("roles").isArray()).isTrue();
        assertThat(node.path("permissions").isArray()).isTrue();
        assertThat(node.path("roles").isEmpty()).isTrue();
        assertThat(node.path("permissions").isEmpty()).isTrue();
    }

    @Test
    void assignRoles_takesEffectImmediatelyAfterCacheEvict() throws Exception {
        // 普通用户：注册登录，此时无任何权限
        String email = uniqueEmail("rbac");
        String userToken = registerAndLoginGetToken(email, "abc123");
        Long userId = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)).getId();

        // 1. 无权限：访问需 user:page 的接口返回 403
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isForbidden());

        // 2. 超管给该用户分配 ADMIN 角色（走接口 → 事务提交后缓存失效）
        String adminTok = adminToken();
        mockMvc.perform(put("/api/users/{id}/roles", userId)
                        .header("Authorization", bearerHeader(adminTok))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roleIds", List.of(adminRoleId())))))
                .andExpect(status().isOk());

        // 3. 原 token 再次访问：缓存已失效 → 回源拿到 ADMIN 权限 → 200
        mockMvc.perform(get("/api/users").header("Authorization", bearerHeader(userToken)))
                .andExpect(status().isOk());
    }
}
