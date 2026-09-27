package com.qsx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 验证码 Lua 脚本注册。
 *
 * <p>两个脚本同为 {@code DefaultRedisScript<String>} 类型，**必须用 @Qualifier 按 Bean 名注入**：
 * 按类型注入会因存在多个同类型 Bean 而抛 NoUniqueBeanDefinitionException
 * （与 {@code AuthSessionLuaConfig} 同一约定）。
 *
 * <p>脚本文件位于本模块（{@code qsx-module-system/src/main/resources/lua/}）——
 * **脚本必须与使用方同模块**，否则 ClassPathResource 加载失败。
 *
 * <p>注意：DefaultRedisScript 实现 InitializingBean，Bean 创建时只校验脚本文件存在，
 * 不校验 Lua 语法——语法错误要到首次 EVAL 才暴露，因此必须有真的执行两条脚本的测试。
 */
@Configuration
public class CaptchaLuaConfig {

    /** 发送脚本返回 'OK' / 'GAP' / 'QUOTA' */
    @Bean
    public DefaultRedisScript<String> captchaSendScript() {
        return load("lua/captcha_send.lua");
    }

    /** 校验脚本返回 'OK' / 'MISS' / 'WRONG' / 'LOCK' */
    @Bean
    public DefaultRedisScript<String> captchaVerifyScript() {
        return load("lua/captcha_verify.lua");
    }

    private DefaultRedisScript<String> load(String path) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(String.class);
        return script;
    }
}
