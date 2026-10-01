package com.qsx;

import com.qsx.config.ProdSecurityGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产形态配置守卫。
 *
 * <p>与 {@code MailChainConfigTest} 同一体例：**不依赖任何外部设施**，也不会因环境缺席而跳过，
 * 因此始终会被执行。守卫拦的是两类「配置忘了改、而且不会报错」的情形：
 * <ol>
 *   <li>值提供了，但值本身是仓库里公开的（{@link ProdSecurityGuard} 在启动期拦）；</li>
 *   <li>值压根没提供（{@code application-prod.yml} 用**无默认值**的占位符，解析失败即启动失败）。</li>
 * </ol>
 *
 * <p>刻意**不去真的激活 prod profile** 来验证第 1 类：那会先撞上第 2 类的
 * {@code ${DB_PASSWORD}} 解析失败，失败原因变成"数据库口令没给"而不是我们要验的那条。
 * 守卫做成静态纯函数正是为了这里能直接喂各种组合。
 */
class ProdSecurityGuardTest extends BaseIntegrationTest {

    private static final String GOOD_IMPORT_PASSWORD = "imp0rt-Passw0rd";
    private static final String GOOD_DB_PASSWORD = "db-Passw0rd";

    @Test
    @DisplayName("三项都合规时放行")
    void verify_passesWithProdSafeValues() {
        assertThatCode(() -> ProdSecurityGuard.verify(false, GOOD_IMPORT_PASSWORD, GOOD_DB_PASSWORD))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("captcha.debug=true 拒绝启动：验证码会随响应直返给任意调用者")
    void verify_rejectsCaptchaDebug() {
        assertThatThrownBy(() -> ProdSecurityGuard.verify(true, GOOD_IMPORT_PASSWORD, GOOD_DB_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qsx.captcha.debug");
    }

    @Test
    @DisplayName("导入口令仍是仓库公开值时拒绝启动（含空值）")
    void verify_rejectsKnownImportPassword() {
        assertThatThrownBy(() -> ProdSecurityGuard.verify(
                false, ProdSecurityGuard.KNOWN_IMPORT_DEFAULT_PASSWORD, GOOD_DB_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMPORT_DEFAULT_PASSWORD");

        assertThatThrownBy(() -> ProdSecurityGuard.verify(false, "", GOOD_DB_PASSWORD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("数据库口令仍是开发口令时拒绝启动（含空值）")
    void verify_rejectsKnownDatasourcePassword() {
        assertThatThrownBy(() -> ProdSecurityGuard.verify(
                false, GOOD_IMPORT_PASSWORD, ProdSecurityGuard.KNOWN_DATASOURCE_DEFAULT_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");

        assertThatThrownBy(() -> ProdSecurityGuard.verify(false, GOOD_IMPORT_PASSWORD, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("application-prod.yml 的敏感项必须用**无默认值**的占位符")
    void prodProfileFile_hasNoFallbackDefaults() throws IOException {
        String yaml = readProdConfig();

        // 带 `:默认值` 的占位符等于把公开的默认口令又写回生产配置，fail-fast 就没了。
        // 这条守卫就是固定「宁可起不来也不要裸奔」这个决策
        assertThat(valueOf(yaml, "username"))
                .as("数据库账号必须来自环境变量").matches("\\$\\{[A-Z_]+}");
        assertThat(valueOf(yaml, "password"))
                .as("数据库口令必须来自环境变量，且**不得**带 :默认值").matches("\\$\\{[A-Z_]+}");
        assertThat(valueOf(yaml, "default-password"))
                .as("导入默认口令必须来自环境变量，且**不得**带 :默认值").matches("\\$\\{[A-Z_]+}");
    }

    @Test
    @DisplayName("application-prod.yml 必须显式关闭验证码调试通道")
    void prodProfileFile_disablesCaptchaDebug() throws IOException {
        assertThat(valueOf(readProdConfig(), "debug"))
                .as("生产配置里必须显式写着 false——依赖默认值等于把这条交给运气")
                .isEqualTo("false");
    }

    private String readProdConfig() throws IOException {
        return new String(new ClassPathResource("application-prod.yml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 取顶层键（缩进后的）第一个匹配值；找不到返回 null */
    private static String valueOf(String yaml, String key) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}
