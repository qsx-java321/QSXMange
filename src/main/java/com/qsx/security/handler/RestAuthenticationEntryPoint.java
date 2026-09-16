package com.qsx.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qsx.common.constant.SecurityConstants;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.OperationLog;
import com.qsx.domain.entity.User;
import com.qsx.security.model.SecurityUser;
import com.qsx.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证（401）JSON 处理，并补记一条失败操作日志
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;
    private final OperationLogService operationLogService;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper, OperationLogService operationLogService) {
        this.objectMapper = objectMapper;
        this.operationLogService = operationLogService;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Result<Void> result = Result.fail(ResultCode.UNAUTHORIZED);
        response.getWriter().write(objectMapper.writeValueAsString(result));

        // 补记：未登录（401）请求不进 Controller，AOP 覆盖不到，在此落库。
        // 例外：认证链路因基础设施异常（Redis 不可达等）判为未认证时不落库——
        // 故障期间 100% 请求都会走到这里，逐条异步落库会在队列打满后转同步写库，
        // 把「Redis 故障」放大成「数据库与 Web 线程池一起被打满」
        if (request.getAttribute(SecurityConstants.AUTH_INFRA_ERROR_ATTR) != null) {
            log.warn("认证链路基础设施异常，跳过该 401 的操作日志落库, uri={}", request.getRequestURI());
            return;
        }
        recordAccessLog(request, 401, ResultCode.UNAUTHORIZED.getMessage());
    }

    /**
     * 补记一条失败访问日志（异步落库）
     */
    private void recordAccessLog(HttpServletRequest request, int httpStatus, String errorMsg) {
        OperationLog log = new OperationLog();
        // 当前登录用户（未登录场景为空）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityUser securityUser
                && securityUser.getUser() != null) {
            User user = securityUser.getUser();
            log.setUserId(user.getId());
            log.setUsername(user.getEmail());
        }
        log.setHttpStatus(httpStatus);
        log.setSuccess(0);
        log.setErrorMsg(errorMsg);
        log.setMethod(request.getMethod());
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        log.setUrl(queryString == null ? uri : uri + "?" + queryString);
        log.setCostMs(0);
        operationLogService.asyncSave(log);
    }
}