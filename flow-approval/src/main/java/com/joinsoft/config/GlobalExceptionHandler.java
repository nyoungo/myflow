package com.joinsoft.config;

import com.joinsoft.common.api.vo.Result;
import com.joinsoft.common.exception.JoinsoftException;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.AuthorizationException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 统一异常处理，保证接口始终返回 Result 结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(JoinsoftException.class)
    public Result<?> handleBiz(JoinsoftException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(AuthorizationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAuthz(AuthorizationException e) {
        return Result.error(403, "没有权限执行该操作");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<?> handleValidation(Exception e) {
        if (e instanceof MethodArgumentNotValidException) {
            String msg = ((MethodArgumentNotValidException) e).getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getDefaultMessage).collect(Collectors.joining("; "));
            return Result.error(msg);
        }
        String msg = ((BindException) e).getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(Collectors.joining("; "));
        return Result.error(msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleOther(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统异常：" + e.getMessage());
    }
}
