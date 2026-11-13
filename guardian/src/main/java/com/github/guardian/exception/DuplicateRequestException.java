package com.github.guardian.exception;

/**
 * 命中幂等：同一 key 的请求已在执行中或已完成且不允许重复执行。
 */
public class DuplicateRequestException extends GuardianException {

    public DuplicateRequestException(String message) {
        super(message);
    }
}
