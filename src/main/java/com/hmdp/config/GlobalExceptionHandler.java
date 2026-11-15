package com.hmdp.config;

import com.hmdp.pojo.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 捕获上传文件大小超出 Spring Multipart 限制异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件大小超出空间限制: {}", e.getMessage());
        return Result.fail("上传图片大小超过空间限制，单张图片不能超过 5MB(单次上传不能超过 10MB)");
    }

    /**
     * 兜底通用运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error("运行时异常: ", e);
        return Result.fail(e.getMessage() != null ? e.getMessage() : "系统内部错误");
    }
}
