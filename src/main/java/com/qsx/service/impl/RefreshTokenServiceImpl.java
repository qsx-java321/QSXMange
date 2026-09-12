package com.qsx.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.config.properties.JwtProperties;
import com.qsx.security.model.RefreshSession;
import com.qsx.security.token.JwtTokenProvider;
import com.qsx.service.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 刷新会话服务实现
 *
 * 设计要点：
 * - key = qsx:auth:refresh:{userId}（单端登录：同一用户仅一个会话，新登录覆盖旧会话）
 * - value = RefreshSession 的 JSON（仅存 SHA-256 哈希，原始 refresh token 不落库）
 * - TTL = refresh-expiration（7d），每次轮换重置实现滑动续期
 * - firstLoginTs = 首次登录时间，超出 refresh-max-lifetime（30d）强制重新登录
 * - **fail-closed**：与权限缓存（fail-open 降级查库）相反，本服务 Redis 异常一律拒绝
 *   （登录不发放 / 续期不通过），会话凭证宁可不可用，不可错误放行
 */
@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);

    private static final String KEY_PREFIX = "qsx:auth:refresh:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;

    public RefreshTokenServiceImpl(StringRedisTemplate stringRedisTemplate,
                                   ObjectMapper objectMapper,
                                   JwtTokenProvider jwtTokenProvider,
                                   JwtProperties jwtProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public String issue(Long userId) {
        String rawToken = jwtTokenProvider.generateRefreshToken();
        RefreshSession session = new RefreshSession();
        session.setHash(sha256Hex(rawToken));
        session.setFirstLoginTs(System.currentTimeMillis());
        try {
            stringRedisTemplate.opsForValue().set(key(userId),
                    objectMapper.writeValueAsString(session),
                    Duration.ofMillis(jwtProperties.getRefreshExpiration()));
        } catch (Exception e) {
            // fail-closed：会话存储不可用则不发放 refresh，登录整体失败（安全优先）
            log.error("写入刷新会话失败, userId={}", userId, e);
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        return rawToken;
    }

    @Override
    public String rotate(Long userId, String rawToken) {
        String key = key(userId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
            }
            RefreshSession session = objectMapper.readValue(json, RefreshSession.class);

            // hash 不匹配：无效令牌或已被轮换/覆盖（旧 refresh 重放即失效）
            if (session.getHash() == null || !session.getHash().equals(sha256Hex(rawToken))) {
                throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
            }

            // 绝对有效上限：自首次登录起超过 refresh-max-lifetime，吊销会话强制重新登录
            long now = System.currentTimeMillis();
            if (now - session.getFirstLoginTs() > jwtProperties.getRefreshMaxLifetime()) {
                stringRedisTemplate.delete(key);
                throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
            }

            // 轮换：生成新令牌，保留首次登录时间，重置 TTL（滑动续期）
            String newRawToken = jwtTokenProvider.generateRefreshToken();
            RefreshSession newSession = new RefreshSession();
            newSession.setHash(sha256Hex(newRawToken));
            newSession.setFirstLoginTs(session.getFirstLoginTs());
            stringRedisTemplate.opsForValue().set(key,
                    objectMapper.writeValueAsString(newSession),
                    Duration.ofMillis(jwtProperties.getRefreshExpiration()));
            return newRawToken;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // fail-closed：Redis 异常拒绝续期，与权限缓存降级策略刻意相反
            log.error("刷新会话校验失败, userId={}", userId, e);
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
    }

    @Override
    public void remove(Long userId) {
        try {
            stringRedisTemplate.delete(key(userId));
        } catch (Exception e) {
            // 由调用方决定降级策略：logout/delete 捕获降级，kick 传播为 500（明确失败）
            log.error("删除刷新会话失败, userId={}", userId, e);
            throw new IllegalStateException("删除刷新会话失败, userId=" + userId, e);
        }
    }

    private String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}