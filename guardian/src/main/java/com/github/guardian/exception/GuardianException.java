package com.github.guardian.exception;

/**
 * guardian 所有异常的基类。
 *
 * <p>刻意做成 unchecked：切面在业务调用链上抛异常，不应该污染业务方法的
 * {@code throws} 声明。业务侧要区分处理时按子类 catch。
 */
public class GuardianException extends RuntimeException {

    public GuardianException(String message) {
        super(message);
    }

    public GuardianException(String message, Throwable cause) {
        super(message, cause);
    }
}
