package com.qsx.web.advice;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.domain.entity.OperationLog;
import com.qsx.domain.entity.User;
import com.qsx.security.model.SecurityUser;
import com.qsx.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final OperationLogService operationLogService;

    public GlobalExceptionHandler(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常
     *
     * 注意：@Valid/@RequestBody 的参数校验发生在 AOP 切入点（参数解析层）之前，
     * OperationLogAspect 的 proceed() 不会执行，无法记录，因此在此补记一条 400 失败日志。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        recordAccessLog(400, message);
        return Result.fail(400, message);
    }

    /**
     * 方法级鉴权拒绝（@PreAuthorize），返回 403
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        return Result.fail(ResultCode.FORBIDDEN);
    }

    /**
     * 后端兜底异常
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }

    /**
     * 补记一条失败访问日志（异步落库）。供绕过 AOP 切面的异常场景（如参数校验失败）使用。
     */
    private void recordAccessLog(int httpStatus, String errorMsg) {
        OperationLog log = new OperationLog();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityUser securityUser
                && securityUser.getUser() != null) {
            User user = securityUser.getUser();
            log.setUserId(user.getId());
            log.setUsername(user.getEmail());
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            log.setMethod(request.getMethod());
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            log.setUrl(queryString == null ? uri : uri + "?" + queryString);
        }
        log.setHttpStatus(httpStatus);
        log.setSuccess(0);
        log.setErrorMsg(errorMsg);
        log.setCostMs(0);
        operationLogService.asyncSave(log);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}