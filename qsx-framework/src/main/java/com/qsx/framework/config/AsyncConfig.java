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
     * <p>刻意用小队列 + CallerRunsPolicy：验证码是用户正在等待的动作，
     * **丢码比慢一拍更糟**（用户会卡在「收不到验证码」且无从重试）。
     * 队列打满时由请求线程同步投递，仅损失异步性。与日志池策略保持一致。
     */
    @Bean("captchaMailExecutor")
    public ThreadPoolTaskExecutor captchaMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("cap-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}