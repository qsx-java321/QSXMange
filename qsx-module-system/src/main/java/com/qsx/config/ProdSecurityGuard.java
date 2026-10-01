package com.qsx.config;

import org.springframework.util.StringUtils;

/**
 * 生产形态的启动期自检。
 *
 * <p><b>为什么需要它</b>：本应用有一批「本地联调很方便、生产形态致命」的配置——
 * 验证码 debug 通道直返验证码、导入默认口令是仓库里公开的值、数据库口令是开发口令。
 * 它们都不是代码缺陷，而是**配置忘了改**，而配置忘了改是不会报错的：应用照常启动、
 * 照常服务，只是每一次请求都在裸奔。
 *
 * <p><b>为什么逻辑要与 Bean 拆开</b>：{@code verify} 做成静态纯函数，测试可以直接喂
 * 各种组合断言抛不抛，**不必真的激活 prod profile**——真去激活会先撞上
 * {@code application-prod.yml} 里那个没有默认值的 {@code ${DB_PASSWORD}}，
 * 于是失败原因变成"数据库连不上"而不是"我们要验的那条"，验证就失真了。
 *
 * <p>本类只管「值本身是否可接受」；「值根本没提供」由配置文件的
 * **无默认值占位符**在更早的阶段拦下（解析不到即启动失败）。
 */
public final class ProdSecurityGuard {

    /**
     * 仓库里公开的导入默认口令（application.yml 的 `:默认值` 与 init.sql 的注释都写着它）。
     * 生产形态下必须换掉。
     */
    public static final String KNOWN_IMPORT_DEFAULT_PASSWORD = "qsx123456";

    /** 本地 Docker 开发库口令，仓库里公开；生产形态下必须换掉 */
    public static final String KNOWN_DATASOURCE_DEFAULT_PASSWORD = "123456";

    private ProdSecurityGuard() {
    }

    /**
     * 生产形态自检：任一条件命中即抛 {@link IllegalStateException}，让应用**拒绝启动**。
     *
     * <p>错误信息一律写清「为什么危险」与「怎么改」——启动失败是最后一道防线，
     * 如果只说"配置不合法"，运维的第一反应会是把它注释掉。
     *
     * @param captchaDebug          {@code qsx.captcha.debug}
     * @param importDefaultPassword {@code import.default-password}
     * @param datasourcePassword    {@code spring.datasource.password}
     */
    public static void verify(boolean captchaDebug,
                              String importDefaultPassword,
                              String datasourcePassword) {
        if (captchaDebug) {
            throw new IllegalStateException(
                    "生产形态禁止开启验证码调试通道：qsx.captcha.debug=true 时验证码随接口响应直接返回，"
                            + "任意调用者都能拿到别人的验证码，邮箱验证形同虚设。"
                            + "请去掉该配置（application-prod.yml 已显式置 false），"
                            + "并确认 spring.mail 指向真实邮件服务商。");
        }
        if (isKnown(KNOWN_IMPORT_DEFAULT_PASSWORD, importDefaultPassword)) {
            throw new IllegalStateException(
                    "生产形态禁止使用仓库里公开的导入默认口令：import.default-password 仍是 '"
                            + KNOWN_IMPORT_DEFAULT_PASSWORD + "'，它写在 application.yml 与 sql/init.sql 里，"
                            + "等于给所有导入账号签发同一个已知口令。请通过环境变量 IMPORT_DEFAULT_PASSWORD 提供新值。");
        }
        if (isKnown(KNOWN_DATASOURCE_DEFAULT_PASSWORD, datasourcePassword)) {
            throw new IllegalStateException(
                    "生产形态禁止使用开发库口令：spring.datasource.password 仍是 '"
                            + KNOWN_DATASOURCE_DEFAULT_PASSWORD + "'，它写在 application.yml 里且账号通常是 root。"
                            + "请通过环境变量 DB_PASSWORD 提供真实口令。");
        }
    }

    /** 空白也算命中：空口令与默认口令一样不可接受；口令为空只在"显式设成空串"时出现 */
    private static boolean isKnown(String known, String actual) {
        return !StringUtils.hasText(actual) || known.equals(actual);
    }
}
