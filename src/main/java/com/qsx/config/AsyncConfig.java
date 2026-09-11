package com.qsx.config;

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
}