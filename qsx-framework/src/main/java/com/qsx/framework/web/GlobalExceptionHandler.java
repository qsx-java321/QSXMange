package com.qsx.framework.web;

import com.qsx.common.exception.BusinessException;
import com.qsx.common.log.AccessLogCommand;
import com.qsx.common.log.AccessLogRecorder;
import com.qsx.common.result.Result;
import com.qsx.common.result.ResultCode;
import com.qsx.security.model.SecurityUser;
import com.qsx.security.port.AuthUserAccount;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    private final AccessLogRecorder accessLogRecorder;

    public GlobalExceptionHandler(AccessLogRecorder accessLogRecorder) {
        this.accessLogRecorder = accessLogRecorder;
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
     * 请求体无法解析：JSON 语法错误，或字段类型不匹配（典型是枚举取值非法，如 scene 传了未定义的场景）
     *
     * <p>不加此处理器会落到兜底的 {@link #handleException}，把「客户端传错参数」
     * 报成 500 系统异常——既误导调用方，也让监控把参数错误计成服务端故障。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        recordAccessLog(400, ResultCode.BAD_REQUEST.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST);
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
        AccessLogCommand command = new AccessLogCommand();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SecurityUser securityUser
                && securityUser.getAccount() != null) {
            AuthUserAccount account = securityUser.getAccount();
            command.setUserId(account.id());
            command.setUsername(account.email());
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            command.setMethod(request.getMethod());
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            command.setUrl(queryString == null ? uri : uri + "?" + queryString);
        }
        command.setHttpStatus(httpStatus);
        command.setSuccess(0);
        command.setErrorMsg(errorMsg);
        command.setCostMs(0);
        accessLogRecorder.record(command);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}