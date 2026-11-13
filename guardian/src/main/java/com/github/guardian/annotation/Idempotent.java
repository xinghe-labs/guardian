package com.github.guardian.annotation;

import com.github.guardian.enums.ExceptionPolicy;
import com.github.guardian.enums.IdempotentMode;
import com.github.guardian.enums.PendingStrategy;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 幂等控制。
 *
 * <p><b>数值参数的默认值是哨兵 {@code -1}，不是 10 / 30 这种真实值。</b>
 * 原因：注解的 {@code default} 是编译期常量，若写成 {@code ttl() default 600}，
 * 就<b>无法区分"用户没写"和"用户显式写了 600"</b> —— 那么
 * {@code guardian.idempotent.default-ttl} 永远没有生效路径，配置项形同虚设。
 * 优先级：注解显式正数 &gt; {@code guardian.*.default-*} &gt; 硬编码兜底。
 *
 * <p><b>{@code @Retention(RUNTIME)} 不是可选项。</b>Java 注解默认保留策略是
 * {@code CLASS} 而非 {@code RUNTIME}，而切面要读注解属性（{@code key()} / {@code ttl()}）
 * 就得靠运行时反射。本项目切面用 {@code @Around("@annotation(idem)")} 绑定注解参数，
 * 缺了 RUNTIME 会<b>启动直接失败</b>（{@code Annotation type ... does not have runtime retention}）
 * —— 反而是好事。危险的是"只匹配不绑定"的写法：切面照常触发，但拿不到任何属性。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 幂等 key 的 SpEL 表达式，如 {@code "#req.orderNo"} 或
     * {@code "#root.methodName + ':' + #userId"}。
     *
     * <p>依赖编译时 {@code -parameters} 才能拿到参数名，否则 {@code #req} 解析不到。
     */
    String key();

    /** 占位有效期。{@code -1} 取 {@code guardian.idempotent.default-ttl}（默认 10m）。 */
    long ttl() default -1;

    /** {@link #ttl()} 的单位，仅在 {@code ttl} 显式写正数时生效。 */
    TimeUnit unit() default TimeUnit.SECONDS;

    /** MVP 只实现 {@link IdempotentMode#FINGERPRINT}（MVP 范围）。 */
    IdempotentMode mode() default IdempotentMode.FINGERPRINT;

    /** true 时业务完成后把返回值写回存储，重复请求直接返回该值。 */
    boolean cacheResult() default false;

    /** 首次请求仍在执行时，重复请求的策略。 */
    PendingStrategy onPending() default PendingStrategy.FAIL;

    /** {@code onPending = SPIN} 时最多自旋等待的毫秒数。 */
    long pendingSpinMillis() default 0;

    /** 业务方法抛异常时占位的处理策略。 */
    ExceptionPolicy onException() default ExceptionPolicy.RELEASE;

    /** 命中幂等时抛出的异常消息。 */
    String message() default "请勿重复提交";
}
