package com.qsx.service.captcha;

import com.qsx.common.constant.CaptchaScene;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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

    /**
     * 验证码本体。
     *
     * <p>四个构造器都<b>先归一化再拼键</b>（而不是让调用方自己记得调 {@link #normalizeEmail}）：
     * 键的形态必须只由一个地方决定。历史上构造器不做折叠、只有服务层折叠，于是任何直接调
     * 构造器的调用方（测试取码、运维脚本）只要传入未折叠的邮箱，读写的就不是同一个键——
     * 表现为「码明明发出去了却取不到」，且不报错（测试里会静默回落到占位码）。
     */
    public static String code(CaptchaScene scene, String email) {
        return PREFIX + scene.name() + ":" + normalizeEmail(email);
    }

    /** 失败次数（校验失败 INCR，成功/重发时清除） */
    public static String attempt(CaptchaScene scene, String email) {
        return PREFIX + "attempt:" + scene.name() + ":" + normalizeEmail(email);
    }

    /** 发送间隔标记（SET NX EX，占位失败即「刚发过」） */
    public static String limit(CaptchaScene scene, String email) {
        return PREFIX + "limit:" + scene.name() + ":" + normalizeEmail(email);
    }

    /**
     * 当日发送计数。
     *
     * <p>刻意用**日期串**而非裸键 + 24h 滑动 TTL：若每次发送都 EXPIRE 续期，
     * 日限就退化成滑动窗口，持续调用者永远撞不到上限。
     */
    public static String daily(CaptchaScene scene, String email) {
        return PREFIX + "daily:" + LocalDate.now().format(DAY_FORMATTER) + ":" + scene.name()
                + ":" + normalizeEmail(email);
    }

    /**
     * 客户端 IP 的当日发送计数（跨场景、跨邮箱累计）。
     *
     * <p>IP 不做归一化：它来自 {@code request.getRemoteAddr()}，本身就是规范化形态
     * （IPv6 的不同写法由 JDK 统一输出，不需要我们再折叠）。
     */
    public static String ipDaily(String clientIp) {
        return PREFIX + "ipdaily:" + LocalDate.now().format(DAY_FORMATTER) + ":" + clientIp;
    }

    /**
     * 邮箱归一化——**发送与校验必须用同一口径**，否则算出的键不同、校验必然失败。
     *
     * <p>折叠大小写是必须的：{@code sys_user.email} 列的 collation 是
     * {@code utf8mb4_general_ci}，库内比较本就大小写不敏感（实测 {@code 'a@x.com' = 'A@X.COM'}
     * 为真），因此**登录也大小写不敏感**——用户完全可能以小写邮箱登录、而库里存着混合大小写。
     * 若键保留原样大小写，发码侧（用客户端原样输入）与校验侧（用 {@code account.email()}，
     * 库中原样）就会算出两个不同的键，表现为「验证码刚发出就说过期」（1028），
     * 且该文案会把排查方向完全带偏。
     *
     * <p><b>反例记录</b>：早期注释断言「邮箱在库中是按原样存储与比较的，故不折叠」——
     * 该前提不成立，已因此产生「邮箱大小写不一致时改密验证码通道永久失效」的缺陷。
     * 改这里之前请先确认库的 collation 语义，
     * 不要照抄旧注释的推理。
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
