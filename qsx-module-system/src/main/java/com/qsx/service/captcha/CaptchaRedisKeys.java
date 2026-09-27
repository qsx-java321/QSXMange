package com.qsx.service.captcha;

import com.qsx.common.constant.CaptchaScene;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 验证码 Redis 键构造器：前缀常量的唯一来源（对齐 {@code AuthRedisKeys} 的写法）。
 *
 * <pre>
 * qsx:auth:cap:{scene}:{email}            -> 6 位验证码      TTL 5 分钟
 * qsx:auth:cap:attempt:{scene}:{email}    -> 失败次数        TTL 与码同生共死
 * qsx:auth:cap:limit:{scene}:{email}      -> 发送间隔标记    TTL 60 秒
 * qsx:auth:cap:daily:{yyyyMMdd}:{scene}:{email} -> 当日发送计数 TTL 24 小时
 * </pre>
 *
 * <p>全部键统一挂 {@code qsx:auth:} 前缀（认证域同一命名空间），
 * {@link #PREFIX} 同时是测试清理扫描的前缀——新增键必须落在它之下，
 * 否则会跨用例残留并污染限流类断言。
 */
public final class CaptchaRedisKeys {

    /** 统一前缀。测试清理（BaseIntegrationTest）按 {@code PREFIX + "*"} 扫描删除 */
    public static final String PREFIX = "qsx:auth:cap:";

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private CaptchaRedisKeys() {
    }

    /** 验证码本体 */
    public static String code(CaptchaScene scene, String email) {
        return PREFIX + scene.name() + ":" + email;
    }

    /** 失败次数（校验失败 INCR，成功/重发时清除） */
    public static String attempt(CaptchaScene scene, String email) {
        return PREFIX + "attempt:" + scene.name() + ":" + email;
    }

    /** 发送间隔标记（SET NX EX，占位失败即「刚发过」） */
    public static String limit(CaptchaScene scene, String email) {
        return PREFIX + "limit:" + scene.name() + ":" + email;
    }

    /**
     * 当日发送计数。
     *
     * <p>刻意用**日期串**而非裸键 + 24h 滑动 TTL：若每次发送都 EXPIRE 续期，
     * 日限就退化成滑动窗口，持续调用者永远撞不到上限。
     */
    public static String daily(CaptchaScene scene, String email) {
        return PREFIX + "daily:" + LocalDate.now().format(DAY_FORMATTER) + ":" + scene.name() + ":" + email;
    }

    /**
     * 邮箱归一化——**发送与校验必须用同一口径**，否则算出的键不同、校验必然失败。
     * 只做去空白：邮箱在库中是按原样存储与比较的，此处不引入大小写折叠，
     * 避免出现「验证码键用小写、库查询用原样」的第二套规则。
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim();
    }
}
