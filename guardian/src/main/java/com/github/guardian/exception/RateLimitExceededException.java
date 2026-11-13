package com.github.guardian.exception;

/**
 * 超出限流配额。
 */
public class RateLimitExceededException extends GuardianException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
