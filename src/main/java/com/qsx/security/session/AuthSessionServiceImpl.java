package com.qsx.security.session;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.config.properties.AuthSessionProperties;
import com.qsx.security.token.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 双 token 有状态会话实现。
 *
 * 全部多键操作走 Lua 原子脚本（签发 / 轮换 / 清理），消除并发竞态：
 * 改造前 refresh 是「GET -> 改 -> SET」的非原子读改写，一次与踢人（裸 DEL）
 * 并发的刷新会把刚被踢掉的会话重新 SET 回来并续上新的 7 天 TTL。
 *
 * 脚本参数约定（三条脚本通用）：
 * <ul>
 *   <li>所有 ARGV 均为字符串：StringRedisTemplate 用 StringRedisSerializer 序列化参数，
 *       传 Long/Integer 会在触达 Redis 之前抛 ClassCastException；</li>
 *   <li>前缀由 {@link AuthRedisKeys} 提供并以 ARGV 传入，脚本内拼接动态键名；</li>
 *   <li>时间戳由 Java 传入（不用 redis.call('TIME')，避免脚本确定性争议）。</li>
 * </ul>
 *
 * 失败策略：**fail-closed**，Redis 异常一律向上抛出，绝不降级放行。
 */
@Service
public class AuthSessionServiceImpl implements AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionServiceImpl.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile(TOKEN_REGEX);

    private static final String STATUS_OK = "OK";
    private static final String STATUS_EXPIRED = "EXPIRED";

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<String> issueScript;
    private final DefaultRedisScript<String> rotateScript;
    private final DefaultRedisScript<String> removeScript;
    private final TokenProvider tokenProvider;
    private final AuthSessionProperties properties;

    public AuthSessionServiceImpl(StringRedisTemplate stringRedisTemplate,
                                  @Qualifier("authSessionIssueScript") DefaultRedisScript<String> issueScript,
                                  @Qualifier("authSessionRotateScript") DefaultRedisScript<String> rotateScript,
                                  @Qualifier("authSessionRemoveScript") DefaultRedisScript<String> removeScript,
                                  TokenProvider tokenProvider,
                                  AuthSessionProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.issueScript = issueScript;
        this.rotateScript = rotateScript;
        this.removeScript = removeScript;
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @Override
    public AuthSession issue(Long userId) {
        String accessToken = tokenProvider.generateAccessToken();
        String refreshToken = tokenProvider.generateRefreshToken();
        try {
            stringRedisTemplate.execute(issueScript,
                    List.of(AuthRedisKeys.session(userId),
                            AuthRedisKeys.at(accessToken),
                            AuthRedisKeys.rt(refreshToken)),
                    String.valueOf(userId),
                    accessToken,
                    refreshToken,
                    String.valueOf(properties.atTtlSeconds()),
                    String.valueOf(properties.rtTtlSeconds()),
                    String.valueOf(System.currentTimeMillis()),
                    AuthRedisKeys.AT_PREFIX,
                    AuthRedisKeys.RT_PREFIX);
        } catch (Exception e) {
            // fail-closed：签发不出来就不放行（Redis 异常在此不降级）
            log.error("签发会话失败（Redis 异常，fail-closed）, userId={}", userId, e);
            throw new IllegalStateException("签发会话失败, userId=" + userId, e);
        }
        return new AuthSession(userId, accessToken, refreshToken);
    }

    @Override
    public AuthSession rotate(String refreshToken) {
        if (!isWellFormed(refreshToken)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }
        String newAccessToken = tokenProvider.generateAccessToken();
        String newRefreshToken = tokenProvider.generateRefreshToken();

        String result;
        try {
            result = stringRedisTemplate.execute(rotateScript,
                    List.of(AuthRedisKeys.rt(refreshToken),
                            AuthRedisKeys.at(newAccessToken),
                            AuthRedisKeys.rt(newRefreshToken)),
                    refreshToken,
                    newAccessToken,
                    newRefreshToken,
                    String.valueOf(properties.atTtlSeconds()),
                    String.valueOf(properties.rtTtlSeconds()),
                    String.valueOf(properties.maxLifetimeMillis()),
                    String.valueOf(System.currentTimeMillis()),
                    AuthRedisKeys.AT_PREFIX,
                    AuthRedisKeys.RT_PREFIX,
                    AuthRedisKeys.SESSION_PREFIX);
        } catch (Exception e) {
            // fail-closed：Redis 异常按 1019 处理（与改造前一致），日志留痕以便区分「故障」与「令牌失效」
            log.error("刷新会话失败（Redis 异常，fail-closed）, rt={}", mask(refreshToken), e);
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        // 脚本返回 'status|userId'
        String status = null;
        String rawUserId = null;
        if (result != null) {
            int sep = result.indexOf('|');
            status = sep < 0 ? result : result.substring(0, sep);
            rawUserId = sep < 0 ? null : result.substring(sep + 1);
        }
        if (STATUS_OK.equals(status)) {
            Long userId = parseUserId(rawUserId);
            if (userId == null) {
                log.error("刷新会话返回结果缺少 userId, result={}", result);
                throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
            }
            return new AuthSession(userId, newAccessToken, newRefreshToken);
        }
        if (STATUS_EXPIRED.equals(status)) {
            log.info("会话超过绝对上限，已强制作废, rt={}", mask(refreshToken));
        }
        // INVALID、EXPIRED 与任何非预期返回值统一 1019：不向客户端泄露失败细节
        throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
    }

    @Override
    public void remove(Long userId) {
        try {
            stringRedisTemplate.execute(removeScript,
                    List.of(AuthRedisKeys.session(userId)),
                    AuthRedisKeys.AT_PREFIX,
                    AuthRedisKeys.RT_PREFIX);
        } catch (Exception e) {
            log.error("清理会话失败（Redis 异常，fail-closed）, userId={}", userId, e);
            throw new IllegalStateException("清理会话失败, userId=" + userId, e);
        }
    }

    @Override
    public Long findUserIdByAccessToken(String accessToken) {
        if (!isWellFormed(accessToken)) {
            return null;
        }
        // 不捕获异常：调用方（认证过滤器）需要区分「令牌不存在」与「Redis 不可达」
        return parseUserId(stringRedisTemplate.opsForValue().get(AuthRedisKeys.at(accessToken)));
    }

    @Override
    public Long findUserIdByRefreshToken(String refreshToken) {
        if (!isWellFormed(refreshToken)) {
            return null;
        }
        return parseUserId(stringRedisTemplate.opsForValue().get(AuthRedisKeys.rt(refreshToken)));
    }

    // ---------- 私有辅助 ----------

    private boolean isWellFormed(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    private Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.error("会话映射值非法，按无效令牌处理, raw={}", raw);
            return null;
        }
    }

    /** 日志脱敏：令牌前 8 位足以定位问题，且泄露 32 位后仍有 224 位熵不可枚举 */
    private String mask(String token) {
        return token == null || token.length() <= 8 ? "***" : token.substring(0, 8) + "***(masked)";
    }
}
