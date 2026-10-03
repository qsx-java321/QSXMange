package com.qsx.service.email;

import com.qsx.common.constant.CaptchaScene;
import com.qsx.config.properties.CaptchaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * SMTP 投递实现：本地 Mailpit 与线上真实邮箱**共用这一份**。
 *
 * <p>切换服务商只改 {@code spring.mail.host/port/username/password}（见 application.yml），
 * 本类代码零改动；发件地址不同时改 {@code qsx.captcha.from-address}。
 *
 * <p>条件装配与 {@link DebugEmailService} 互斥（一个 matchIfMissing=true、一个 havingValue=true），
 * 保证任何模式下**恰好一个** EmailService Bean——两个都装配会直接
 * NoUniqueBeanDefinitionException 启动失败。
 */
@Component
@ConditionalOnProperty(name = "qsx.captcha.debug", havingValue = "false", matchIfMissing = true)
public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final CaptchaProperties properties;

    public SmtpEmailService(JavaMailSender mailSender, CaptchaProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * 异步投递验证码邮件。
     *
     * <p>流程：组装简单文本邮件（标题用场景文案）→ 经 JavaMailSender 提交 SMTP →
     * 记录日志。投递失败在异步线程内捕获并 ERROR 留痕，不影响接口结果（码已落 Redis，
     * 用户可重发）；「提交成功」不等于「已送达」，本地可用 Mailpit Web UI 确认。
     *
     * <p>{@code @Async} 生效前提：本 Bean 由**外部 Bean**（CaptchaServiceImpl）调用。
     * Spring 的异步基于代理，同类内部自调用会让 @Async 静默失效（见 OperationLogServiceImpl
     * 的教训）。
     *
     * @param scene 验证码场景（决定邮件标题中的场景文案）
     * @param email 收件人邮箱
     * @param code  验证码明文（6 位数字）
     */
    @Override
    @Async("captchaMailExecutor")
    public void sendVerificationCode(CaptchaScene scene, String email, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getFromAddress());
            message.setTo(email);
            message.setSubject("【QSXManager】" + scene.label() + "验证码");
            message.setText("您的" + scene.label() + "验证码是：" + code
                    + "，" + properties.getTtl().toMinutes() + " 分钟内有效。\n"
                    + "请勿将验证码透露给任何人。若非本人操作，请忽略本邮件。");
            mailSender.send(message);
            log.info("验证码邮件已提交投递, scene={}, email={}", scene, mask(email));
        } catch (Exception e) {
            // 异步线程内的异常没人接：不吞掉就会只剩一行框架日志，且无法与业务日志关联。
            // 注意「提交成功」≠「已送达」——本地可用 Mailpit Web UI（8025）确认。
            log.error("验证码邮件投递失败（接口已返回成功，用户将收不到验证码）, scene={}, email={}",
                    scene, mask(email), e);
        }
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
