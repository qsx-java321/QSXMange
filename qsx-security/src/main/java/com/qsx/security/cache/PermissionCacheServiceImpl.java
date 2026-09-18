package com.qsx.security.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.security.config.properties.RbacCacheProperties;
import com.qsx.security.model.PermissionCacheData;
import com.qsx.security.port.UserAuthorityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * RBAC 权限缓存服务实现
 *
 * 设计要点：
 * - key = qsx:auth:perm:{userId}（用 userId 而非 email：读取/失效点均持有 userId，改邮箱/禁用无需处理 key）
 * - value = PermissionCacheData 的 JSON（仅缓存权限码，用户行/密码/状态仍实时查库）
 * - 空权限也回填缓存，防止未绑定角色用户每请求回源（防穿透）
 * - Redis 异常降级回源 MySQL，缓存故障不影响业务
 * - enabled=false 时退化为实时查库（load 不读写 Redis，evict 为空操作）
 */
@Service
public class PermissionCacheServiceImpl implements PermissionCacheService {

    private static final Logger log = LoggerFactory.getLogger(PermissionCacheServiceImpl.class);

    private static final String KEY_PREFIX = "qsx:auth:perm:";

    private final UserAuthorityRepository userAuthorityRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RbacCacheProperties properties;

    public PermissionCacheServiceImpl(UserAuthorityRepository userAuthorityRepository,
                                      StringRedisTemplate stringRedisTemplate,
                                      ObjectMapper objectMapper,
                                      RbacCacheProperties properties) {
        this.userAuthorityRepository = userAuthorityRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public PermissionCacheData load(Long userId) {
        if (!properties.isEnabled()) {
            return loadFromDb(userId);
        }
        try {
            String key = key(userId);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, PermissionCacheData.class);
            }
            // 未命中回源并回填（含空集合）
            PermissionCacheData data = loadFromDb(userId);
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(data), properties.getTtl());
            return data;
        } catch (Exception e) {
            // Redis 连接/序列化异常降级为实时查库，不中断业务
            log.warn("读取权限缓存失败，降级查库, userId={}", userId, e);
            return loadFromDb(userId);
        }
    }

    @Override
    public void evictUsers(Collection<Long> userIds) {
        if (!properties.isEnabled() || userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            stringRedisTemplate.delete(userIds.stream().map(this::key).collect(Collectors.toList()));
        } catch (Exception e) {
            log.warn("失效权限缓存失败, userIds={}（依赖 TTL 兜底）", userIds, e);
        }
    }

    // ---------- 私有辅助 ----------

    private PermissionCacheData loadFromDb(Long userId) {
        PermissionCacheData data = new PermissionCacheData();
        data.setRoles(userAuthorityRepository.selectRoleCodes(userId));
        data.setPermissions(userAuthorityRepository.selectPermissionCodes(userId));
        return data;
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
