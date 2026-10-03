package com.qsx.service.email;

import com.qsx.common.constant.CaptchaScene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 调试直返实现：**不投递**，验证码由接口响应直接返回（{@code qsx.captcha.debug=true} 时装配）。
 *
 * <p>存在的意义是「行为确实不同」——它不是 Mailpit 的替代品，
 * 而是「连 SMTP 都不想连」的联调逃生口（例如 Mailpit 未启动、或想用脚本一把跑通流程）。
 */
@Component
@ConditionalOnProperty(name = "qsx.captcha.debug", havingValue = "true")
public class DebugEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(DebugEmailService.class);

    public DebugEmailService() {
        // 启动横幅：这是一个必须一眼看到的危险配置，不能只写在配置文件注释里
        log.warn("================ 验证码调试模式已启用 ================");
        log.warn(" qsx.captcha.debug=true：不会投递任何邮件，");
        log.warn(" 验证码将直接出现在 POST /auth/captcha 的响应 data.code 中。");
        log.warn(" 此模式下任何人拿到验证码即可通过校验，仅限本地联调！");
        log.warn("======================================================");
    }

    /**
     * 空实现：不投递任何邮件——验证码由 {@code CaptchaServiceImpl} 直接放进响应返回。
     *
     * @param scene 验证码场景（本实现不使用）
     * @param email 收件人邮箱（本实现不使用）
     * @param code  验证码明文（本实现不使用，由服务层回传响应）
     */
    @Override
    public void sendVerificationCode(CaptchaScene scene, String email, String code) {
        // 刻意什么都不做：码由 CaptchaServiceImpl 交给调用方返回
    }
}
