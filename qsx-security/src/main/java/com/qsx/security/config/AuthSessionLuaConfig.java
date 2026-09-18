package com.qsx.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 会话 Lua 脚本注册。
 *
 * 三个脚本同为 {@code DefaultRedisScript} 类型，**必须用 @Qualifier 按 Bean 名注入**：
 * 按类型注入会因存在多个同类型 Bean 而抛 NoUniqueBeanDefinitionException。
 *
 * 注意：DefaultRedisScript 实现 InitializingBean，Bean 创建时只校验脚本文件存在，
 * 不校验 Lua 语法——语法错误要到首次 EVAL 才暴露，因此 SessionLuaTest 必须真的执行三条脚本。
 */
@Configuration
public class AuthSessionLuaConfig {

    @Bean
    public DefaultRedisScript<String> authSessionIssueScript() {
        return load("lua/auth_session_issue.lua", String.class);
    }

    /**
     * 轮换脚本返回 'status|userId' 字符串（见脚本注释）
     */
    @Bean
    public DefaultRedisScript<String> authSessionRotateScript() {
        return load("lua/auth_session_rotate.lua", String.class);
    }

    @Bean
    public DefaultRedisScript<String> authSessionRemoveScript() {
        return load("lua/auth_session_remove.lua", String.class);
    }

    private <T> DefaultRedisScript<T> load(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
