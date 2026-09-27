package com.qsx.service.email;

import com.qsx.common.constant.CaptchaScene;

/**
 * 邮件投递端口：业务层只依赖本接口，投递方式对业务透明。
 *
 * <p>当前实现按「行为是否真的不同」划分，而非按环境划分：
 * <ul>
 *   <li>{@link SmtpEmailService}——SMTP 投递。**本地 Mailpit 与线上真实邮箱共用这一份**：
 *       Mailpit 本身就是真实的 SMTP 服务（只是不外发），与真实服务商的差异只有
 *       host/port/凭据，全部由 {@code spring.mail.*} 承担，切换时改配置即可、代码零改动。</li>
 *   <li>{@link DebugEmailService}——调试直返，不投递（行为确实不同），由 {@code qsx.captcha.debug} 装配。</li>
 * </ul>
 *
 * <p>将来若接入**只提供 HTTP API** 的服务商（SendGrid、阿里云邮件推送等），
 * 再加第三个实现并用条件装配切换——那时条件装配才真正承担「切实现」的职责。
 *
 * <p>接口刻意只声明「发送验证码邮件」这一件事：将来要发通知邮件时另开方法或另建接口，
 * 不要把本接口扩成万能发信器（与「common 不要变成垃圾桶」同一条纪律）。
 */
public interface EmailService {

    /**
     * 发送验证码邮件。
     *
     * <p>实现方**不应抛出异常打断主流程**（调用发生在验证码已落 Redis 之后）：
     * 投递失败只影响用户能否收到信，不应让接口返回失败、更不应让已落库的业务回滚。
     *
     * @param scene 场景（决定邮件标题中的用途文案）
     * @param email 收件邮箱
     * @param code  6 位验证码
     */
    void sendVerificationCode(CaptchaScene scene, String email, String code);
}
