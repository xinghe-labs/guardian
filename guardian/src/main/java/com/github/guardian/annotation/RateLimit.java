package com.github.guardian.annotation;

import com.github.guardian.enums.RateLimitStrategy;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 滑动窗口限流。
 *
 * <p>数值参数默认值是哨兵 {@code -1}，理由同 {@link Idempotent}：
 * 写上真实值就无法区分"没写"与"显式写了默认值"，配置项会失效。
 *
 * <p><b>切面顺序：{@code RateLimitAspect} 必须排在 {@code IdempotentAspect} 之前</b>
 * （{@code @Order} 10 vs 20）。先限流再判重，否则恶意重放能先打到 Redis 判重，
 * 把 Redis 打满。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** SpEL 表达式。按方法维度可写 {@code "#root.methodName"}，按用户可写 {@code "#userId"}。 */
    String key();

    /** 窗口内允许的请求数。{@code -1} 取 {@code guardian.rate-limit.default-permits}（默认 10）。 */
    int permits() default -1;

    /** 窗口长度。{@code -1} 取 {@code guardian.rate-limit.default-window}（默认 1s）。 */
    long window() default -1;

    /** {@link #window()} 的单位。 */
    TimeUnit unit() default TimeUnit.SECONDS;

    /** MVP 只实现 {@link RateLimitStrategy#REJECT}（MVP 范围）。 */
    RateLimitStrategy strategy() default RateLimitStrategy.REJECT;

    /** 超限时抛出的异常消息。 */
    String message() default "请求过于频繁";
}
