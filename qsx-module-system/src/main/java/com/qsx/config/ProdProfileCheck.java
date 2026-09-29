package com.qsx.config;

import com.qsx.config.properties.CaptchaProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅在 {@code prod} profile 下装配：把 {@link ProdSecurityGuard} 的自检接到启动流程上。
 *
 * <p>本类**只在 prod 下存在**，所以本地与测试上下文完全不受影响（230 例基线零改动）。
 * 本地想预演生产形态时用 {@code --spring.profiles.active=prod}，并接受它要求提供
 * {@code DB_PASSWORD} / {@code IMPORT_DEFAULT_PASSWORD} 等环境变量这一事实。
 */
@Component
@Profile("prod")
public class ProdProfileCheck {

    private static final Logger log = LoggerFactory.getLogger(ProdProfileCheck.class);

    private final CaptchaProperties captchaProperties;
    private final String importDefaultPassword;
    private final String datasourcePassword;

    public ProdProfileCheck(CaptchaProperties captchaProperties,
                            @Value("${import.default-password}") String importDefaultPassword,
                            @Value("${spring.datasource.password}") String datasourcePassword) {
        this.captchaProperties = captchaProperties;
        this.importDefaultPassword = importDefaultPassword;
        this.datasourcePassword = datasourcePassword;
    }

    @PostConstruct
    void verify() {
        ProdSecurityGuard.verify(captchaProperties.isDebug(), importDefaultPassword, datasourcePassword);
        log.info("生产形态自检通过：验证码调试通道已关闭、导入默认口令与数据库口令均非仓库公开值");
    }
}
