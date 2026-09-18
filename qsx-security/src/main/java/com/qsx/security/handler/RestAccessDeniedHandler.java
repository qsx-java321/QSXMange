package com.qsx.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.common.log.AccessLogCommand;
import com.qsx.common.log.AccessLogRecorder;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 无权限（403）JSON 处理，并补记一条失败操作日志
 *
 * 注意：本项目 @PreAuthorize 拒权由 OperationLogAspect 捕获，
 * 此处兜底 URL 级授权规则触发的 403 场景。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AccessLogRecorder accessLogRecorder;

    public RestAccessDeniedHandler(ObjectMapper objectMapper, AccessLogRecorder accessLogRecorder) {
        this.objectMapper = objectMapper;
        this.accessLogRecorder = accessLogRecorder;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> result = Result.fail(ResultCode.FORBIDDEN);
        response.getWriter().write(objectMapper.writeValueAsString(result));

        // 补记：URL 级 403 兜底
        recordAccessLog(request, 403, ResultCode.FORBIDDEN.getMessage());
    }

    /**
     * 补记一条失败访问日志（异步落库）
     */
    private void recordAccessLog(HttpServletRequest request, int httpStatus, String errorMsg) {
        AccessLogCommand command = new AccessLogCommand();
        // 当前登录用户（越权场景必有用户，未登录走 401）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityUser securityUser
                && securityUser.getAccount() != null) {
            AuthUserAccount account = securityUser.getAccount();
            command.setUserId(account.id());
            command.setUsername(account.email());
        }
        command.setHttpStatus(httpStatus);
        command.setSuccess(0);
        command.setErrorMsg(errorMsg);
        command.setMethod(request.getMethod());
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        command.setUrl(queryString == null ? uri : uri + "?" + queryString);
        command.setCostMs(0);
        accessLogRecorder.record(command);
    }
}