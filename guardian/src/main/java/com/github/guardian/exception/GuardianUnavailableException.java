package com.github.guardian.exception;

/**
 * 存储不可用（Redis 超时、断连等），当前状态<b>未知</b>。
 *
 * <p>和 {@link DuplicateRequestException} 必须分开，这是踩过的坑：
 * 早期实现 {@code try/catch} 后统一 {@code return false}，结果 Redis 挂掉时
 * 所有请求都被当成"重复提交"，报错还是"请勿重复提交"，排查了两小时。
 *
 * <p>三种返回必须区分：
 * <ul>
 *   <li>SET NX 返回 OK &rarr; 首次请求</li>
 *   <li>SET NX 返回 nil &rarr; 已存在（别人在跑或已跑完）</li>
 *   <li><b>抛异常 &rarr; 状态未知</b> &rarr; 按各组件 {@code fail-open} 决定放行还是拒绝，
 *       绝不能当成 nil</li>
 * </ul>
 */
public class GuardianUnavailableException extends GuardianException {

    public GuardianUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GuardianUnavailableException(String message) {
        super(message);
    }
}
