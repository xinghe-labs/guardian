package com.github.guardian.aspect;

import com.github.guardian.annotation.Idempotent;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.ExceptionPolicy;
import com.github.guardian.enums.IdempotentMode;
import com.github.guardian.enums.PendingStrategy;
import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.KeyResolver;
import com.github.guardian.support.ResultSerializer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;

/**
 * {@code @Idempotent} 的实现。
 *
 * <p>核心是"两态 + 一次占位"：{@code SET NX} 抢到就是首次，抢不到就读当前状态决定怎么办。
 * 状态只有两个值，但表达三态：
 *
 * <pre>
 * key 不存在              → 首次（还没人抢，或已过期）
 * key = PENDING           → 进行中（首次请求还在跑）
 * key = DONE:{"v":...}    → 已完成（直接返回结果）
 * </pre>
 *
 * <p><b>失败路径比成功路径更容易出错</b>，三个点必须对上：
 * <ol>
 *   <li>业务抛异常 → 默认 {@code RELEASE} 释放占位，允许立即重试。
 *       不释放的话一次失败会把 key 锁死整个 ttl，用户"重试"反而被拒。</li>
 *   <li>Redis 不可用 → 抛 {@link GuardianUnavailableException}，由 {@code fail-open} 决定。
 *       绝不能 catch 后返回 false —— 那等于把"状态未知"当成"重复提交"，
 *       Redis 抖动一次就全站拒绝（这个坑：fail-open 配错方向，Redis 抖动一次就全站拒绝）。</li>
 *   <li>进程崩溃 → 只能等 ttl 过期。这就是 ttl 不能设太长的原因。</li>
 * </ol>
 *
 * <p><b>顺序</b>：本切面排在 {@code @DistributedLock} 之外（order 更小 = 更外层），
 * 因为切面顺序要求"拿锁失败时无条件释放幂等占位"—— 只有幂等在外层，锁的失败才能
 * 被幂等看到。三者顺序：RateLimit(100) → Idempotent(200) → Lock(300)。
 */
@Aspect
@Order(IdempotentAspect.ORDER)
public class IdempotentAspect {

    /** 比限流外层靠内，比分布式锁外层靠外。见类注释的"顺序"一节。 */
    public static final int ORDER = 200;

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /** 占位标记：首次请求正在执行。 */
    public static final String PENDING = "PENDING";

    /** 完成标记前缀：后面跟 JSON 序列化的返回值。 */
    public static final String DONE_PREFIX = "DONE:";

    private final GuardianStorage storage;
    private final GuardianProperties properties;
    private final KeyResolver keyResolver;
    private final GuardianKeys keys;
    private final ResultSerializer codec;

    public IdempotentAspect(GuardianStorage storage, GuardianProperties properties,
                            KeyResolver keyResolver, GuardianKeys keys, ResultSerializer codec) {
        this.storage = storage;
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.keys = keys;
        this.codec = codec;
    }

    @Around("@annotation(idem)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idem) throws Throwable {
        if (idem.mode() == IdempotentMode.TOKEN) {
            // MVP 边界：MVP 不实现 Token 预申请模式，传入即抛，
            // 不留"配了但静默不生效"的坑
            throw new UnsupportedOperationException(
                    "IdempotentMode.TOKEN 未实现（需要 GET /guardian/token 预申请接口，"
                            + "属 MVP 之外的加分项。"
                            + "当前请使用默认的 IdempotentMode.FINGERPRINT");
        }

        Method method = resolveMethod(pjp);
        String resolved = keyResolver.resolve(idem.key(), method, pjp.getArgs(), pjp.getTarget());
        String key = keys.idempotent(resolved);
        Duration ttl = ttlOf(idem.ttl());

        Acquire acquired = acquire(key, ttl);
        if (acquired == Acquire.BYPASS) {
            // fail-open：存储不可用且配置为放行。业务照跑，幂等这次不生效
            return pjp.proceed();
        }
        if (acquired == Acquire.FIRST) {
            return executeFirst(pjp, key, idem, ttl, method);
        }

        Optional<String> stored = readQuietly(key);
        if (stored.isEmpty()) {
            // 竞态窗口：setIfAbsent 失败后、getResult 之前 key 过期了。
            // 不重试的话这次请求会被无理由判为重复，而实际上没人持有它。
            Acquire retry = acquire(key, ttl);
            if (retry == Acquire.BYPASS) {
                return pjp.proceed();
            }
            if (retry == Acquire.FIRST) {
                return executeFirst(pjp, key, idem, ttl, method);
            }
            throw new DuplicateRequestException(idem.message());
        }

        String value = stored.get();
        if (PENDING.equals(value)) {
            return handlePending(key, idem, method);
        }
        if (value.startsWith(DONE_PREFIX)) {
            return handleDone(value, idem, method);
        }
        // 不认识的占位内容（被别的程序写过同一个 key？）
        log.warn("幂等 key {} 的内容不是本框架写入的格式，按重复请求处理: {}", key, value);
        throw new DuplicateRequestException(idem.message());
    }

    /** 首次执行：跑业务 → 回填结果；抛异常 → 按 onException 处理。 */
    private Object executeFirst(ProceedingJoinPoint pjp, String key, Idempotent idem,
                                Duration ttl, Method method) throws Throwable {
        try {
            Object result = pjp.proceed();
            if (idem.cacheResult() && method.getReturnType() != void.class) {
                // 回填是"覆写"而不是"新建"，且 ttl 重置 —— 否则业务耗时接近 ttl 时，
                // 结果会先于占位过期，重复请求读不到结果又被判为重复
                storage.setResult(key, DONE_PREFIX + codec.serialize(result), ttl);
            }
            return result;
        } catch (Throwable t) {
            // 为什么要有 businessNeverStarted 这个例外：切面顺序是
            // RateLimit(10) → Idempotent(20) → Lock(30)。幂等先占了位，锁才去等；
            // 锁没等到就抛异常时<b>业务从未执行</b>，而幂等 key 会原样留到 ttl（默认 10 分钟）。
            // 用户立刻重试反被封为“请勿重复提交”——这不是幂等，是把失败请求永久锁死。
            // 所以这种情况无条件释放，不受 onException 控制。
            if (idem.onException() == ExceptionPolicy.RELEASE || businessNeverStarted(t)) {
                // 业务没做成，占位必须释放，否则用户重试会被自己上一次失败挡住
                safeRelease(key);
            }
            throw t;
        }
    }

    /**
     * 异常是否意味着“业务方法根本没开始”。
     *
     * <p>沿 cause 链找：切面抛的异常过 AOP 代理后可能被包一层
     * （如 {@code UndeclaredThrowableException}），只看最外层会漏。
     */
    private static boolean businessNeverStarted(Throwable t) {
        Throwable current = t;
        // 最多走 8 层，避免病态自引用导致死循环
        for (int i = 0; current != null && i < 8; i++) {
            if (current instanceof LockAcquireFailedException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 已有请求在跑：按 onPending 决定拒绝还是自旋等结果。 */
    private Object handlePending(String key, Idempotent idem, Method method) throws Throwable {
        if (idem.onPending() == PendingStrategy.FAIL) {
            throw new DuplicateRequestException(idem.message());
        }

        long deadline = System.currentTimeMillis() + Math.max(0L, idem.pendingSpinMillis());
        do {
            Optional<String> current = readQuietly(key);
            if (current.isEmpty()) {
                // 占位在自旋期间过期了。业务可能仍在跑，不能放行（会重复执行），
                // 也不能等（key 已不存在，等不到结果）
                throw new DuplicateRequestException(idem.message());
            }
            String v = current.get();
            if (v.startsWith(DONE_PREFIX)) {
                return handleDone(v, idem, method);
            }
            if (System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                // 自旋间隔固定 20ms：再短没意义（一次 Redis 往返就要 1ms 量级），
                // 再长会明显拉高重复请求的响应时间
                Thread.sleep(Math.min(20L, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DuplicateRequestException(idem.message());
            }
        } while (System.currentTimeMillis() <= deadline);

        throw new DuplicateRequestException(idem.message());
    }

    /** 已完成：cacheResult 时返回缓存结果，否则同样拒绝。 */
    private Object handleDone(String value, Idempotent idem, Method method) {
        if (!idem.cacheResult()) {
            throw new DuplicateRequestException(idem.message());
        }
        return codec.deserialize(value.substring(DONE_PREFIX.length()), method.getGenericReturnType());
    }

    /** 占位结果三态。合并成 boolean 会把 fail-open 错当成"重复请求"。 */
    private enum Acquire {
        /** 抢到，本次是首次。 */
        FIRST,
        /** 已被占用，是重复请求。 */
        DUPLICATE,
        /** 存储不可用且配置为 fail-open，本次直接放行、不做幂等。 */
        BYPASS
    }

    /**
     * 占位，处理 Redis 不可用。
     *
     * <p><b>为什么不能用 boolean</b>："抢到了"和"存储挂了但配置放行"都要继续跑业务，
     * 但语义完全不同（前者要回填结果、后者不能回填）。早期用 boolean 时，
     * fail-open 返回 false 被当成"重复请求"，结果 Redis 一挂，幂等注解反而变成
     * "全站拒绝提交"—— 正好和 fail-open 的意图相反。
     */
    private Acquire acquire(String key, Duration ttl) {
        try {
            return storage.setIfAbsent(key, PENDING, ttl) ? Acquire.FIRST : Acquire.DUPLICATE;
        } catch (GuardianUnavailableException e) {
            if (properties.getIdempotent().isFailOpen()) {
                log.warn("幂等存储不可用，按 fail-open 放行: key={}", key, e);
                return Acquire.BYPASS;
            }
            throw e;
        }
    }

    /** 读当前状态；Redis 不可用时按 fail-open 降级成"没有记录"。 */
    private Optional<String> readQuietly(String key) {
        try {
            return storage.getResult(key);
        } catch (GuardianUnavailableException e) {
            if (properties.getIdempotent().isFailOpen()) {
                log.warn("幂等存储不可用，按 fail-open 当作无记录: key={}", key, e);
                return Optional.empty();
            }
            throw e;
        }
    }

    private void safeRelease(String key) {
        try {
            storage.releaseIfMatch(key, PENDING);
        } catch (RuntimeException e) {
            // 释放失败不能覆盖业务原本的异常（那才是用户要看的），
            // 占位最终由 ttl 兜底
            log.warn("释放幂等占位失败，将由 ttl 兜底过期: key={}", key, e);
        }
    }

    /**
     * 注解 {@code ttl()} 默认 -1 表示"没写，用全局默认值"。
     *
     * <p>哨兵值不能省：注解的 {@code default} 是编译期常量，写成真实值
     * （如 {@code default 600000}）就无法区分"用户没写"和"用户显式写了 10 分钟"，
     * {@code guardian.idempotent.default-ttl} 就永远没有生效路径。
     */
    private Duration ttlOf(long annotationTtlMillis) {
        return annotationTtlMillis > 0
                ? Duration.ofMillis(annotationTtlMillis)
                : properties.getIdempotent().getDefaultTtl();
    }

    /**
     * 取真实方法。
     *
     * <p>{@code getSignature().getMethod()} 在 JDK 代理 / 接口方法上拿到的是接口声明，
     * 那上面可能没有真实参数名（接口编译时未必带 {@code -parameters}），
     * {@code #req} 就解析不到。所以再走一层 {@link AopUtils#getMostSpecificMethod}。
     */
    private Method resolveMethod(ProceedingJoinPoint pjp) {
        Method declared = ((MethodSignature) pjp.getSignature()).getMethod();
        Object target = pjp.getTarget();
        return target == null ? declared : AopUtils.getMostSpecificMethod(declared, target.getClass());
    }
}
