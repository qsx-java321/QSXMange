package com.qsx.service.impl;

import com.qsx.common.constant.CaptchaScene;
import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.config.properties.CaptchaProperties;
import com.qsx.service.CaptchaService;
import com.qsx.service.captcha.CaptchaRedisKeys;
import com.qsx.service.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * 邮箱验证码实现：Redis 存储 + Lua 原子限流/校验。
 *
 * <p>多键操作全部走 Lua（`captcha_send.lua` / `captcha_verify.lua`）：
 * <ul>
 *   <li>发送侧——限流标记、当日计数、落码、清错次必须原子，
 *       否则会留下无 TTL 的键把某个 {scene}:{email} 永久锁死；</li>
 *   <li>校验侧——GET→比对→计数→删除必须原子，否则同一张码可被并发双花（用后即焚失效）。</li>
 * </ul>
 *
 * <p>脚本参数约定（同会话脚本）：ARGV 全为字符串，脚本内 tonumber()。
 */
@Service
public class CaptchaServiceImpl implements CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaServiceImpl.class);

    private static final String SEND_OK = "OK";
    private static final String SEND_GAP = "GAP";
    private static final String SEND_QUOTA = "QUOTA";

    private static final String VERIFY_OK = "OK";
    private static final String VERIFY_LOCK = "LOCK";
    private static final String VERIFY_MISS = "MISS";
    private static final String VERIFY_WRONG = "WRONG";

    /** 6 位验证码的取值上界（含前导零，用 %06d 补齐） */
    private static final int CODE_BOUND = 1_000_000;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<String> sendScript;
    private final DefaultRedisScript<String> verifyScript;
    private final EmailService emailService;
    private final CaptchaProperties properties;

    /**
     * 验证码必须不可预测：可预测的码等于没有验证码，因此用 SecureRandom 而非 Random。
     * 单实例复用（SecureRandom 线程安全），而不是每次调用 new 一个。
     */
    private final SecureRandom random = new SecureRandom();

    public CaptchaServiceImpl(StringRedisTemplate stringRedisTemplate,
                              @Qualifier("captchaSendScript") DefaultRedisScript<String> sendScript,
                              @Qualifier("captchaVerifyScript") DefaultRedisScript<String> verifyScript,
                              EmailService emailService,
                              CaptchaProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.sendScript = sendScript;
        this.verifyScript = verifyScript;
        this.emailService = emailService;
        this.properties = properties;
    }

    @Override
    public String send(CaptchaScene scene, String email) {
        String target = CaptchaRedisKeys.normalizeEmail(email);
        String code = randomCode();

        String result;
        try {
            result = stringRedisTemplate.execute(sendScript,
                    List.of(CaptchaRedisKeys.code(scene, target),
                            CaptchaRedisKeys.attempt(scene, target),
                            CaptchaRedisKeys.limit(scene, target),
                            CaptchaRedisKeys.daily(scene, target)),
                    code,
                    String.valueOf(properties.ttlSeconds()),
                    String.valueOf(properties.sendIntervalSeconds()),
                    String.valueOf(properties.dailyTtlSeconds()),
                    String.valueOf(properties.getDailyLimit()));
        } catch (Exception e) {
            // fail-closed：发不出码就不放行（Redis 异常在此不降级）
            log.error("发送验证码失败（Redis 异常，fail-closed）, scene={}, email={}", scene, mask(target), e);
            throw new IllegalStateException("发送验证码失败, scene=" + scene, e);
        }

        if (SEND_GAP.equals(result) || SEND_QUOTA.equals(result)) {
            // 间隔与日限共用同一业务码：对用户而言「现在别再点了」是同一个动作
            log.info("验证码发送被限流, scene={}, email={}, reason={}", scene, mask(target), result);
            throw new BusinessException(ResultCode.CAPTCHA_SEND_TOO_FREQUENT);
        }
        if (!SEND_OK.equals(result)) {
            log.error("发送验证码返回非预期结果, scene={}, result={}", scene, result);
            throw new IllegalStateException("发送验证码失败, result=" + result);
        }

        // 投递在落码之后：投递方式（SMTP 异步 / 调试直返）对业务透明，
        // 且投递失败不应让接口报错——码已在 Redis 中，用户重发即可
        emailService.sendVerificationCode(scene, target, code);

        // 仅调试模式把码交回调用方（响应直返）；正常路径不返回
        return properties.isDebug() ? code : null;
    }

    @Override
    public void verify(CaptchaScene scene, String email, String code) {
        // 空值直接判无效：不落到 Redis（Lua 参数不接受 null），也不抛 500
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResultCode.CAPTCHA_INVALID);
        }
        String target = CaptchaRedisKeys.normalizeEmail(email);

        String result;
        try {
            result = stringRedisTemplate.execute(verifyScript,
                    List.of(CaptchaRedisKeys.code(scene, target),
                            CaptchaRedisKeys.attempt(scene, target)),
                    code,
                    String.valueOf(properties.getMaxAttempts()),
                    String.valueOf(properties.ttlSeconds()));
        } catch (Exception e) {
            log.error("校验验证码异常（Redis 异常，fail-closed）, scene={}, email={}", scene, mask(target), e);
            throw new IllegalStateException("校验验证码失败, scene=" + scene, e);
        }

        if (VERIFY_OK.equals(result)) {
            return;
        }
        if (VERIFY_LOCK.equals(result)) {
            log.info("验证码错误次数超限已作废, scene={}, email={}", scene, mask(target));
            throw new BusinessException(ResultCode.CAPTCHA_ATTEMPT_EXCEEDED);
        }
        if (VERIFY_MISS.equals(result) || VERIFY_WRONG.equals(result)) {
            // MISS 与 WRONG 统一文案：不向调用方区分「不存在 / 已过期 / 填错」，
            // 也不回显剩余次数——否则等于送给爆破者一个进度条
            log.debug("验证码校验未通过, scene={}, email={}, reason={}", scene, mask(target), result);
            throw new BusinessException(ResultCode.CAPTCHA_INVALID);
        }
        log.error("校验验证码返回非预期结果, scene={}, result={}", scene, result);
        throw new IllegalStateException("校验验证码失败, result=" + result);
    }

    /** 6 位数字，含前导零 */
    private String randomCode() {
        return String.format("%06d", random.nextInt(CODE_BOUND));
    }

    /** 日志脱敏：保留首字符与域名，足够定位问题又不必整条落盘 */
    private String mask(String email) {
        if (email == null) {
            return "null";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
