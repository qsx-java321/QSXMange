package com.qsx.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置
 *
 * 用于操作日志异步落库，避免日志写入拖慢/影响主业务请求。
 * - 队列满时采用 CallerRunsPolicy，由调用线程同步兜底，保证日志不丢
 * （仅损失一点异步性，主请求仍能完成）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 操作日志写入专用线程池
     */
    @Bean("operationLogExecutor")
    public ThreadPoolTaskExecutor operationLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ops-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 验证码邮件投递专用线程池。
     *
     * <p>独立于操作日志池：邮件投递受外部 SMTP 影响，耗时不可控，
     * 与日志落库混用一个池会互相拖累（日志池的队列深度是按 DB 写入配的）。
     *
     * <p><b>拒绝策略刻意与日志池相反，用 AbortPolicy 而不是 CallerRunsPolicy</b>：
     * CallerRuns 会让**请求线程**去同步做 SMTP 投递，而 SMTP 是外部依赖——服务商劣化时
     * 被抓住的 Tomcat 线程会长期不归还，队列打满即等于线程池打满，把"发信慢"放大成"全站不可用"。
     * Abort 则把故障限制在一封邮件上：任务被拒 → 由 {@code CaptchaServiceImpl} 兜住并留 ERROR，
     * **码已经落在 Redis 里**，用户重发即可（代价是要等满 60 秒发送间隔）。
     * 取舍依据见 docs/question-list/01 #22。
     *
     * <p>配合 {@code spring.mail.properties.mail.smtp.*} 的三个超时：即便任务真的被执行，
     * 单次 SMTP 交互也不会无限挂住。
     */
    @Bean("captchaMailExecutor")
    public ThreadPoolTaskExecutor captchaMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("cap-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}