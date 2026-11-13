package com.github.guardian.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁，带看门狗自动续期。
 *
 * <p><b>租期优先级</b>：注解显式正数 &gt; {@code guardian.lock.default-lease}（默认 30s）。
 *
 * <p><b>续期间隔不是固定配置值</b>，而是每次加锁时算：
 * <pre>
 * 生效租期 = 注解 leaseTime &gt; 0 ? 注解值 : guardian.lock.default-lease
 * 实际间隔 = min(guardian.lock.watchdog-renew-interval, 生效租期 / 3)
 * </pre>
 * 一句可解释的规则：<b>续期永远不超过租期的三分之一</b>。配置项从"定值"变成"上限"。
 *
 * <p>为什么不能靠"启动时校验 renew-interval &lt; default-lease"：已实测确认防不住 ——
 * 默认配置 10s &lt; 30s，校验通过；但注解写 {@code leaseTime = 5s} 时真实租期是 5s &lt; 续期 10s，
 * 锁在 t=5s 就过期了，续期线程还没跑到。<b>校验比的是配置对配置，问题却出在注解对配置</b>，
 * 两者不在一个维度上。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /** SpEL 表达式。 */
    String key();

    /** 获取锁最长等待时间。超时抛 {@code LockAcquireFailedException}。 */
    long waitTime() default 3;

    /** 锁租期。{@code -1} 取 {@code guardian.lock.default-lease}（默认 30s）。 */
    long leaseTime() default -1;

    /** {@link #waitTime()} 和 {@link #leaseTime()} 的单位。 */
    TimeUnit unit() default TimeUnit.SECONDS;

    /** 获取锁失败时抛出的异常消息。 */
    String message() default "操作正在进行，请稍后";
}
