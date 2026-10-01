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
 *
 * <p>两处 fail 语义上的注意：
 * <ul>
 *   <li>读到**无法解析**的坏值时不能走"降级且不写缓存"那条路——那会让坏值留到 TTL 到期，
 *       整个 TTL 内每个请求都重复解析失败；必须落到回源 + 回填，由 SET 覆盖它（自愈）；</li>
 *   <li>{@code evict} 失败必须 ERROR 告警：本方法由 AFTER_COMMIT 的监听器调用，
 *       失败即"提交后才失败"，没有重试机会，而受影响用户会带着**已回收的权限**继续通过鉴权。</li>
 * </ul>
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
                PermissionCacheData cached = parse(json, userId);
                if (cached != null) {
                    return cached;
                }
                // 坏值解析失败：**刻意落到下面的回源 + 回填**，由回填的 SET 覆盖它，本次请求即完成自愈。
                // 若改成落进下面的兜底 catch（返回查库结果但放弃写缓存），坏值会原样留到 TTL 到期，
                // 整个 TTL 内每个请求都重复「解析失败 + 2 条 SQL + 一条 WARN」。
            }
            // 未命中回源并回填（含空集合）
            PermissionCacheData data = loadFromDb(userId);
            stringRedisTemplate.opsForValue()
                    .set(key, objectMapper.writeValueAsString(data), properties.getTtl());
            return data;
        } catch (Exception e) {
            // Redis 连接异常降级为实时查库，不中断业务。
            // 注意这里**不做**删除重试：连接都断了，删也删不掉，只会让每个请求多一次失败往返
            log.warn("读取权限缓存失败，降级查库, userId={}", userId, e);
            return loadFromDb(userId);
        }
    }

    /**
     * 解析缓存值；无法解析时返回 {@code null}，让调用方**回源并回填**——回填的 SET 覆盖坏值，
     * 自愈在同一次请求内完成（因此这里不需要"先删再写"那类多余往返）。
     *
     * <p>典型成因：灰度升级改了 {@code PermissionCacheData} 的结构，或该键被外部工具写坏。
     */
    private PermissionCacheData parse(String json, Long userId) {
        try {
            return objectMapper.readValue(json, PermissionCacheData.class);
        } catch (Exception e) {
            log.warn("权限缓存值无法解析，将回源并覆盖, userId={}", userId, e);
            return null;
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
            // ERROR 而非 WARN：本方法由 AFTER_COMMIT 的监听器调用，失败即**提交后**才失败，
            // 没有任何重试机会——受影响用户会带着**已回收的权限**继续通过 @PreAuthorize，
            // 直到 30 分钟 TTL 到期。必须可告警、可检索。
            // 反向提醒：别把它降成 WARN，也别指望"Redis 整体挂了就没事"——整体挂掉时会话链路
            // 已经拒绝认证（fail-closed），但**单次 DEL 失败**时会话链路完全健康，权限照旧放行。
            log.error("失效权限缓存失败：受影响用户将继续持有已回收的权限直至 TTL 到期, userIds={}", userIds, e);
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
