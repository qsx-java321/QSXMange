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

    /**
     * 获取当前登录用户ID，**未登录返回 {@code null}（不抛异常）**。
     *
     * <p>用于需要区分「没有操作者上下文」与「操作者无权限」的判定——例如
     * {@code UserServiceImpl.assignRoles} 的闸 2（授予 ADMIN 需操作者本身是超管）：
     * 程序内直调 service 时没有操作者，应当按 fail-closed 处理成"不是超管"并给出
     * 语义准确的业务码，而不是抛 401 把排查方向带偏。
     */
    public static Long getCurrentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SecurityUser securityUser)) {
            return null;
        }
        AuthUserAccount account = securityUser.getAccount();
        return account == null ? null : account.id();
    }
}