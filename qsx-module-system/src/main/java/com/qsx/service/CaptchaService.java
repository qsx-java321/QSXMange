package com.qsx.service;

import com.qsx.common.constant.CaptchaScene;

/**
 * 邮箱验证码服务：发送 / 校验（场景由调用方显式指定）
 *
 * <p>只负责「验证码」本身，不判断业务前置条件（邮箱是否已注册、是否本人）——
 * 那是各业务流程的语义，由 {@code AuthServiceImpl} 在调用前判定。
 *
 * <p>fail-closed：Redis 异常一律拒绝（发送与校验都失败），与 {@code AuthSessionServiceImpl} 同策略，
 * 不降级放行——验证码是凭据，降级等于放行。
 */
public interface CaptchaService {

    /**
     * 发送验证码：限流 → 落码 → 投递。
     *
     * @return 本次验证码；**仅 debug 模式返回**（响应直返用），正常路径返回 null
     * @throws com.qsx.common.exception.BusinessException 1027 发送过于频繁（间隔未到 / 当日超限）
     */
    String send(CaptchaScene scene, String email);

    /**
     * 校验验证码：成功即用后即焚（同一张码不可重放）。
     *
     * @throws com.qsx.common.exception.BusinessException 1028 无效或已过期／1029 错误次数超限
     */
    void verify(CaptchaScene scene, String email, String code);
}
