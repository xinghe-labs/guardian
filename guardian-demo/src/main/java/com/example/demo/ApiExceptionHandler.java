package com.example.demo;

import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.exception.GuardianException;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.exception.LockLostException;
import com.github.guardian.exception.RateLimitExceededException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 guardian 的异常映射成 HTTP 语义。
 *
 * <p>重点：{@link GuardianUnavailableException} = 503。
 * 默认 fail-closed 下 Redis 挂了会拒绝请求，用户看到的是"服务暂不可用"
 * 而不是 500——把保护措施翻译成客户端能理解的语义，是接入方的责任，
 * starter 只负责抛对异常。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GuardianException.class)
    public ResponseEntity<Map<String, String>> onGuardian(GuardianException e) {
        HttpStatus status;
        if (e instanceof RateLimitExceededException) {
            status = HttpStatus.TOO_MANY_REQUESTS;          // 429
        } else if (e instanceof DuplicateRequestException) {
            status = HttpStatus.CONFLICT;                    // 409
        } else if (e instanceof LockAcquireFailedException) {
            status = HttpStatus.LOCKED;                      // 423
        } else if (e instanceof GuardianUnavailableException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;         // 503 fail-closed
        } else if (e instanceof LockLostException) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;       // 500 锁丢失属于严重态
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .body(Map.of("error", e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage())));
    }
}
