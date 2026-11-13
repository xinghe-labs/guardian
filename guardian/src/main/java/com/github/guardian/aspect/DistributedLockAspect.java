package com.github.guardian.aspect;

import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.exception.LockLostException;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.KeyResolver;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;

/**
 * {@code @DistributedLock} 的实现：{@code SET NX PX} 加锁 + Lua CAS 释放 + 看门狗续期。
 *
 * <p><b>{@code @Order(30)}：排在 {@code @RateLimit}(10) 和 {@code @Idempotent}(20) 之后。</b>
 * 拿锁是要等待的（默认 3 秒），放最后可以避免"白等 3 秒才发现限流超了"。
 * 但这也带来 锁与幂等叠加的副作用：幂等已经占了位，锁却拿不到——业务从未执行，
 * 幂等占位必须释放，否则用户重试会被自己上一次失败挡 10 分钟。
 * 该逻辑在 {@code IdempotentAspect.businessNeverStarted()} 里，靠异常类型判断。
 */
@Aspect
@Order(DistributedLockAspect.ORDER)
public class DistributedLockAspect {

    /** 切面顺序：数值小者先执行。锁在最后。 */
    public static final int ORDER = 30;

    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);

    /** 抢锁失败后的重试间隔。取 50ms：足够小让等待显得"及时"，又不至于把 Redis 打爆。 */
    private static final long RETRY_INTERVAL_MILLIS = 50L;

    private final GuardianStorage storage;
    private final GuardianProperties properties;
    private final KeyResolver keyResolver;
    private final GuardianKeys keys;
    private final Watchdog watchdog;

    public DistributedLockAspect(GuardianStorage storage, GuardianProperties properties,
                                 KeyResolver keyResolver, GuardianKeys keys, Watchdog watchdog) {
        this.storage = storage;
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.keys = keys;
        this.watchdog = watchdog;
    }

    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock lock) throws Throwable {
        Method method = resolveMethod(pjp);
        String resolved = keyResolver.resolve(lock.key(), method, pjp.getArgs(), pjp.getTarget());
        String key = keys.lock(resolved);

        Duration lease = lock.leaseTime() > 0
                // TimeUnit 不是 TemporalUnit，toChronoUnit() 是 Java 9+ 的桥接方法
                ? Duration.of(lock.leaseTime(), lock.unit().toChronoUnit())
                : properties.getLock().getDefaultLease();
        Duration waitTime = Duration.of(Math.max(0L, lock.waitTime()), lock.unit().toChronoUnit());

        // owner 值 = UUID + 线程标识。只写 UUID 也能防"删了别人的锁"，
        // 但排查时看不出是谁持有的；带上线程名，日志和 redis-cli 里都能直接认人。
        String owner = UUID.randomUUID() + "@" + Thread.currentThread().getName();

        boolean acquired;
        try {
            acquired = acquire(key, owner, lease, waitTime);
        } catch (GuardianUnavailableException e) {
            if (properties.getLock().isFailOpen()) {
                // 注意这里<b>不进 try/finally 的释放逻辑</b>：我们压根没拿到锁，
                // 走 finally 会做一次必然失败的 releaseIfMatch（多一次往返 + 一条误导性 warn）
                log.warn("锁存储不可用，按 fail-open 无锁执行: key={}", key, e);
                return pjp.proceed();
            }
            throw e;
        }

        if (!acquired) {
            throw new LockAcquireFailedException(lock.message());
        }

        Watchdog.Handle handle = null;
        try {
            if (properties.getLock().isWatchdogEnabled()) {
                handle = watchdog.watch(key, owner, lease,
                        properties.getLock().effectiveRenewInterval(lease));
            }
            Object result = pjp.proceed();

            // 事后检测：续期线程发现锁丢了只能在这里阻断。
            if (handle != null && handle.isLost()) {
                throw new LockLostException(
                        "锁在执行期间已丢失（续期失败），本次执行结果不可信: key=" + key);
            }
            return result;
        } finally {
            // 顺序不能反：先停续期，再释放。反了会出现"刚释放完又被续期线程写回去"的窗口
            if (handle != null) {
                handle.cancel();
            }
            if (!storage.releaseIfMatch(key, owner)) {
                // 正常情况下不该走到这里（watchdog 已经报过 lost）。兜底记一条：
                // 说明锁在业务期间已过期并被别人持有，业务的一致性需要外部兜底
                log.warn("释放锁时发现已不属于自己（租期已过或已丢失）: key={}", key);
            }
        }
    }

    /**
     * 在 {@code waitTime} 内轮询抢锁。
     *
     * @return 是否抢到
     */
    private boolean acquire(String key, String owner, Duration lease, Duration waitTime) {
        long deadline = System.nanoTime() + waitTime.toNanos();
        while (true) {
            if (storage.setIfAbsent(key, owner, lease)) {
                return true;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                long sleepMillis = Math.min(RETRY_INTERVAL_MILLIS,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                // 恢复中断位再退出：吞掉中断会让上层永远等不到取消信号
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

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
