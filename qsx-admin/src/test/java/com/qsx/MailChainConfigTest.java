package com.qsx;

import com.qsx.framework.config.AsyncConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Properties;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件投递链路的配置守卫。
 *
 * <p>这两个断言不是"测试实现细节"，而是把两条**决定全站可用性**的决策固定下来——
 * 它们都是"改回默认值就悄悄退化"的类型：
 * <ol>
 *   <li><b>SMTP 三个超时必须显式配置</b>：JavaMail 的默认值是无限等待，一旦服务商被 greylist
 *       或链路丢包，投递线程会一直挂住不归还；</li>
 *   <li><b>邮件池必须用 AbortPolicy</b>：CallerRuns 会由请求线程同步做 SMTP 投递，
 *       队列打满 == Tomcat 线程被钉死在外部依赖上 == 全站不可用。</li>
 * </ol>
 * 冒烟用例（{@code CaptchaMailSmokeTest}）只覆盖"Mailpit 在跑时的投递成功路径"，
 * 且 Mailpit 缺席时整类跳过；本类不依赖任何外部设施，始终会跑。
 */
class MailChainConfigTest extends BaseIntegrationTest {

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Test
    @DisplayName("SMTP 连接 / 读 / 写三个超时都必须显式配置（默认是无限等待）")
    void smtpTimeoutsConfigured() {
        Properties properties = mailSender.getJavaMailProperties();

        assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isNotBlank();
        assertThat(properties.getProperty("mail.smtp.timeout")).isNotBlank();
        assertThat(properties.getProperty("mail.smtp.writetimeout")).isNotBlank();
    }

    @Test
    @DisplayName("邮件投递线程池用 AbortPolicy：不把 SMTP 阻塞转嫁给请求线程")
    void captchaMailExecutorUsesAbortPolicy() {
        ThreadPoolTaskExecutor executor = new AsyncConfig().captchaMailExecutor();

        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .as("CallerRuns 会让请求线程去同步做 SMTP 投递，外部依赖劣化时会放大成全站不可用")
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }
}
