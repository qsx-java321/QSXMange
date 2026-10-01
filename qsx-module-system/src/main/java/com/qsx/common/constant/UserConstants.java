package com.qsx.common.constant;

/**
 * 用户域的列宽约束与派生规则（对应 sql/init.sql 中 sys_user 的列定义）。
 *
 * <p>单独立一个类，是因为这些数字是**跨层耦合**的：DTO 校验、昵称兜底、删除时改写邮箱
 * 三处必须用同一个上限，任何一处漏掉都会在 MySQL 的 STRICT 模式下以 1406
 * （Data too long）→ 500「系统繁忙」的形式炸出来，而不是参数错误。历史上两处真实缺陷：
 * <ul>
 *   <li>昵称留空时默认取邮箱，而 {@code nickname} 只有 50 ⇒ 邮箱 &gt; 50 字符就 500
 *       （注册是匿名接口，任何访客都能触发）；</li>
 *   <li>删除时把邮箱改写成 {@code email + "#deleted_" + 13 位毫秒}（固定 22 字符），
 *       而 {@code email} 列只有 128 ⇒ 邮箱 ≥ 107 字符的用户**永远删不掉**。</li>
 * </ul>
 */
public final class UserConstants {

    private UserConstants() {
    }

    /** 昵称列宽（{@code sys_user.nickname VARCHAR(50)}） */
    public static final int NICKNAME_MAX = 50;

    /**
     * 邮箱长度上限（{@code sys_user.email VARCHAR(128)}）。
     *
     * <p>取值必须满足 {@code EMAIL_MAX + 22 ≤ 128}（22 = 删除后缀固定长度：
     * {@code "#deleted_"} 9 字符 + 毫秒时间戳 13 位）：
     * 逻辑删除会用「原邮箱 + 22 字符后缀」释放唯一索引，留不够余量就会让
     * {@code DELETE /api/users/{id}} 在改写那一步 500，账号从此删不掉。
     * 理论边界是 106，此处取 100 是给后缀格式的微调留 6 字符余量——
     * <b>若将来改动删除后缀的格式，必须回到这里重新推导这个数</b>。
     */
    public static final int EMAIL_MAX = 100;

    /**
     * 昵称缺省值：取邮箱，但截断到列宽。
     *
     * <p>调用点共三处（注册、后台建号、Excel 导入），必须全都走这里：
     * 直接把整串邮箱写进 nickname，邮箱 &gt; 50 字符时 INSERT 会撞列宽。
     */
    public static String defaultNickname(String email) {
        if (email == null) {
            return null;
        }
        return email.length() > NICKNAME_MAX ? email.substring(0, NICKNAME_MAX) : email;
    }
}
