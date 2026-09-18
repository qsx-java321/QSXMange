package com.qsx.security.util;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.ResultCode;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 获取当前登录用户行快照（未登录抛异常）
     */
    public static AuthUserAccount getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SecurityUser securityUser)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return securityUser.getAccount();
    }

    /**
     * 获取当前登录用户ID
     */
    public static Long getCurrentUserId() {
        AuthUserAccount account = getCurrentUser();
        return account != null ? account.id() : null;
    }
}