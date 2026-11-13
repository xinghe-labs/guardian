package com.github.guardian.aspect;

import com.github.guardian.annotation.RateLimit;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.RateLimitStrategy;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.RateLimitExceededException;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.KeyResolver;
import java.lang.reflect.Method;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;

/**
 * {@code @RateLimit} 的实现：Lua 滑动窗口计数。
 *
 * <p><b>{@code @Order(10)}：必须排在 {@code @Idempotent}(20) 之前。</b>
 * 镜像的调用顺序是"先限流、再判重"——反过来的话，一次恶意重放要先打一次 Redis
 * 做幂等判重才被限流拦住，限流的保护作用被削掉一半。
 *
 * <p><b>超限的判定来自存储层的原子操作</b>：{@code incrementInWindow} 返回本次请求在窗口内的
 * 序号，{@code > permits} 即超限。"读计数 → 判断 → 写入"在一次 Lua 调用里完成，
 * 不存在并发超发。这正是本项目要演示的那个坑。
 */
@Aspect
@Order(RateLimitAspect.ORDER)
public class RateLimitAspect {

    /** 切面顺序：数值小者先执行。限流必须在幂等之前。 */
    public static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final GuardianStorage storage;
    private final GuardianProperties properties;
    private final KeyResolver keyResolver;
    private final GuardianKeys keys;

    public RateLimitAspect(GuardianStorage storage, GuardianProperties properties,
                           KeyResolver keyResolver, GuardianKeys keys) {
        this.storage = storage;
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.keys = keys;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (rateLimit.strategy() == RateLimitStrategy.WAIT) {
            // 与 IdempotentMode.TOKEN 同样的处理：明说没实现，不留"配了但静默不生效"的坑
            throw new UnsupportedOperationException(
                    "RateLimitStrategy.WAIT 未实现（需要令牌桶 + 阻塞等待，属 MVP 之外的加分项）。当前请使用默认的 REJECT");
        }

        Method method = resolveMethod(pjp);
        String resolved = keyResolver.resolve(rateLimit.key(), method, pjp.getArgs(), pjp.getTarget());
        String key = keys.rateLimit(resolved);

        int permits = rateLimit.permits() > 0
                ? rateLimit.permits()
                : properties.getRateLimit().getDefaultPermits();
        Duration window = rateLimit.window() > 0
                // TimeUnit 不是 TemporalUnit，Duration.of(n, unit) 编译不过；
                // toChronoUnit() 是 Java 9+ 的桥接方法
                ? Duration.of(rateLimit.window(), rateLimit.unit().toChronoUnit())
                : properties.getRateLimit().getDefaultWindow();

        long ordinal;
        try {
            ordinal = storage.incrementInWindow(key, window, permits, System.currentTimeMillis());
        } catch (GuardianUnavailableException e) {
            // 限流的 fail-open 默认是 true：限流失效好过全站 502。
            // 但这是配置项，用户可以把 failOpen 设成 false 要求严格限流
            if (properties.getRateLimit().isFailOpen()) {
                log.warn("限流存储不可用，按 fail-open 放行: key={}", key, e);
                return pjp.proceed();
            }
            throw e;
        }

        if (ordinal > permits) {
            log.debug("限流命中: key={}, 序号={}, permits={}", key, ordinal, permits);
            throw new RateLimitExceededException(rateLimit.message());
        }

        return pjp.proceed();
    }

    /** 与 {@code IdempotentAspect} 同样处理：拿被代理类上的真实方法，注解才读得到。 */
    private static Method resolveMethod(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Class<?> targetClass = AopUtils.getTargetClass(pjp.getTarget());
        Method candidate = signature.getMethod();
        if (targetClass == null || candidate.getDeclaringClass().equals(targetClass)) {
            return candidate;
        }
        Method specific = AopUtils.getMostSpecificMethod(candidate, targetClass);
        return specific != null ? specific : candidate;
    }
}
