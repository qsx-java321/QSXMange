package com.qsx.aspect;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.OperationLog;
import com.qsx.domain.entity.User;
import com.qsx.security.model.SecurityUser;
import com.qsx.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面：包扫描记录进入 Controller 的所有请求
 *
 * - 记录：操作人 / HTTP方法 / URL / 响应状态 / 成功与否 / 失败原因 / 耗时
 * - 落库采用异步（OperationLogService.asyncSave），并通过 log.info 打印一条日志
 * - 未登录(401)在进入 Controller 前被拦截，由 RestAuthenticationEntryPoint 补记
 * - 参数校验失败(400)在参数解析层被拦截，切面无法到达，由 GlobalExceptionHandler 补记
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class OperationLogAspect {

    private final OperationLogService operationLogService;

    public OperationLogAspect(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Around("execution(* com.qsx.web.controller..*.*(..))")
    public Object record(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        // 排除日志管理自身接口（避免“日志的日志”与数据无限膨胀）
        if (request != null && request.getRequestURI().startsWith("/api/logs")) {
            return joinPoint.proceed();
        }

        OperationLog operationLog = initLog(request);
        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            if (result instanceof Result<?> r) {
                boolean ok = r.getCode() == ResultCode.SUCCESS.getCode();
                operationLog.setSuccess(ok ? 1 : 0);
                operationLog.setHttpStatus(200);
                if (!ok) {
                    operationLog.setErrorMsg(r.getMessage());
                }
            } else {
                operationLog.setSuccess(1);
                operationLog.setHttpStatus(200);
            }
            return result;
        } catch (BusinessException e) {
            // 业务失败：HTTP 200 + 非 200 业务码
            operationLog.setSuccess(0);
            operationLog.setHttpStatus(200);
            operationLog.setErrorMsg(e.getMessage());
            throw e;
        } catch (AccessDeniedException e) {
            // @PreAuthorize 拒权：403
            operationLog.setSuccess(0);
            operationLog.setHttpStatus(403);
            operationLog.setErrorMsg("无操作权限");
            throw e;
        } catch (Throwable t) {
            operationLog.setSuccess(0);
            operationLog.setHttpStatus(500);
            operationLog.setErrorMsg(t.getMessage());
            throw t;
        } finally {
            operationLog.setCostMs((int) (System.currentTimeMillis() - start));
            // 控制台 / 日志文件打印一条操作日志
            log.info("操作日志: user={} {} {} http={} success={} cost={}ms err={}",
                    operationLog.getUsername(), operationLog.getMethod(),
                    operationLog.getUrl(), operationLog.getHttpStatus(),
                    operationLog.getSuccess(), operationLog.getCostMs(),
                    operationLog.getErrorMsg());
            // 异步落库，不影响主请求
            operationLogService.asyncSave(operationLog);
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private OperationLog initLog(HttpServletRequest request) {
        OperationLog operationLog = new OperationLog();
        // 当前登录用户信息（未登录 / 匿名接口如 login、register 为空）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityUser securityUser
                && securityUser.getUser() != null) {
            User user = securityUser.getUser();
            operationLog.setUserId(user.getId());
            operationLog.setUsername(user.getEmail());
        }
        if (request != null) {
            operationLog.setMethod(request.getMethod());
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            operationLog.setUrl(queryString == null ? uri : uri + "?" + queryString);
        }
        return operationLog;
    }
}