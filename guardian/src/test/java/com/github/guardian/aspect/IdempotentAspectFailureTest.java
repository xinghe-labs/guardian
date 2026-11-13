package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.guardian.annotation.Idempotent;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.ExceptionPolicy;
import com.github.guardian.enums.IdempotentMode;
import com.github.guardian.enums.PendingStrategy;
import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.LocalGuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * {@code @Idempotent} 的<b>失败路径</b>测试。
 *
 * <p>为什么要单独一个类：覆盖率检查发现 {@code IdempotentAspect} 只有 55% 行覆盖，
 * 未覆盖的 42 行几乎全是失败分支——而这个类的全部价值就在失败分支上。
 * "幂等在失败路径上比成功路径更容易出错"——这句设计约束写在设计文档里，幂等在失败路径上比成功路径更容易出错"，测试却只测了成功路径，
 * 属于典型的"覆盖率看着够、该测的没测"。
 *
 * <p>用可控存储而不是 mock：要验的是"切面在存储抛异常时的行为"，
 * 需要能精确控制<b>第几次调用</b>失败（例如第一次占位成功、第二次失败），
 * mock 的 {@code when()} 对这种序列化场景表达力很差。
 */
class IdempotentAspectFailureTest {

    private FlakyStorage storage;
    private GuardianProperties properties;
    private GuardianKeys keys;
    private OrderService target;

    // ---- 夹具 ----

    record OrderReq(String orderNo) {
    }

    record OrderResult(String status) {
    }

    static class BusinessException extends RuntimeException {
        BusinessException(String message) {
            super(message);
        }
    }

    /**
     * 可控存储：默认行为等同 {@link LocalGuardianStorage}，按开关制造各种故障。
     *
     * <p>每个开关对应一条真实故障：
     * <ul>
     *   <li>{@code failAcquireFromCall} —— Redis 在占位阶段挂掉（连接超时）</li>
     *   <li>{@code failGet} —— 读到一半连接断了</li>
     *   <li>{@code hideExisting} —— 占位失败但读不到值（副本延迟 / 刚被驱逐）</li>
     *   <li>{@code deleteOnGet} —— 读的瞬间 key 过期了（模拟 setIfAbsent 与 getResult 之间的过期窗口）</li>
     *   <li>{@code failRelease} —— 释放时网络断开</li>
     * </ul>
     */
    static class FlakyStorage implements GuardianStorage {

        final LocalGuardianStorage delegate = new LocalGuardianStorage(Clock.systemUTC());
        final AtomicInteger acquireCalls = new AtomicInteger();

        volatile boolean failGet;
        volatile boolean hideExisting;
        volatile boolean deleteOnGet;
        volatile boolean failRelease;
        /** 从第 N 次 {@code setIfAbsent} 起抛（1-based），默认永不失败。 */
        volatile int failAcquireFromCall = Integer.MAX_VALUE;

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            if (acquireCalls.incrementAndGet() >= failAcquireFromCall) {
                throw new GuardianUnavailableException("模拟占位阶段 Redis 不可用");
            }
            return delegate.setIfAbsent(key, value, ttl);
        }

        @Override
        public boolean releaseIfMatch(String key, String value) {
            if (failRelease) {
                throw new IllegalStateException("模拟释放阶段网络断开");
            }
            return delegate.releaseIfMatch(key, value);
        }

        @Override
        public Optional<String> getResult(String key) {
            if (failGet) {
                throw new GuardianUnavailableException("模拟读取阶段 Redis 不可用");
            }
            if (deleteOnGet) {
                delegate.releaseIfMatch(key, "PENDING");
                return Optional.empty();
            }
            if (hideExisting) {
                return Optional.empty();
            }
            return delegate.getResult(key);
        }

        @Override
        public boolean setResult(String key, String value, Duration ttl) {
            return delegate.setResult(key, value, ttl);
        }

        @Override
        public long incrementInWindow(String key, Duration window, int permits, long nowMillis) {
            return delegate.incrementInWindow(key, window, permits, nowMillis);
        }

        @Override
        public boolean renew(String key, String value, Duration ttl) {
            return delegate.renew(key, value, ttl);
        }

        /** 绕过切面直接写占位，用于构造"已有请求在跑"的现场。 */
        void seed(String key, String value) {
            delegate.setIfAbsent(key, value, Duration.ofMinutes(10));
        }

        /** 把已有占位改成 DONE，用于构造"业务跑完了"。 */
        void complete(String key, String serialized) {
            delegate.setResult(key, IdempotentAspect.DONE_PREFIX + serialized, Duration.ofMinutes(10));
        }

        void drop(String key) {
            delegate.releaseIfMatch(key, "PENDING");
            delegate.releaseIfMatch(key, IdempotentAspect.DONE_PREFIX + "{}");
        }
    }

    static class OrderService {
        final AtomicInteger calls = new AtomicInteger();

        @Idempotent(key = "#req.orderNo", cacheResult = true)
        public OrderResult submit(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("OK-" + req.orderNo());
        }

        @Idempotent(key = "#req.orderNo", mode = IdempotentMode.TOKEN)
        public OrderResult tokenMode(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("TOKEN");
        }

        @Idempotent(key = "#req.orderNo", cacheResult = true,
                onPending = PendingStrategy.SPIN, pendingSpinMillis = 3000)
        public OrderResult spinLong(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("SPUN-" + req.orderNo());
        }

        @Idempotent(key = "#req.orderNo", cacheResult = true,
                onPending = PendingStrategy.SPIN, pendingSpinMillis = 60)
        public OrderResult spinShort(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("SPUN-" + req.orderNo());
        }

        /** 业务抛 LockAcquireFailedException，但策略是 KEEP —— 仍必须释放。 */
        @Idempotent(key = "#req.orderNo", cacheResult = true, onException = ExceptionPolicy.KEEP)
        public OrderResult lockFailed(OrderReq req) {
            calls.incrementAndGet();
            throw new LockAcquireFailedException("拿锁超时");
        }

        /** 业务抛普通异常，策略是 RELEASE。 */
        @Idempotent(key = "#req.orderNo", cacheResult = true, onException = ExceptionPolicy.RELEASE)
        public OrderResult businessFailed(OrderReq req) {
            calls.incrementAndGet();
            throw new BusinessException("业务失败 " + req.orderNo());
        }
    }

    // ---- 装配 ----

    @BeforeEach
    void setUp() {
        storage = new FlakyStorage();
        properties = new GuardianProperties();
        keys = new GuardianKeys("test:");
        target = new OrderService();
    }

    private OrderService proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAspect(
                storage, properties, new KeyResolver(), keys, new JacksonResultCodec(null)));
        return factory.getProxy();
    }

    private String keyOf(String orderNo) {
        return keys.idempotent(orderNo);
    }

    // ---- 用例：模式与占位失败 ----

    @Test
    @DisplayName("TOKEN 模式：显式抛 UnsupportedOperationException，不静默降级")
    void tokenModeThrows() {
        assertThatThrownBy(() -> proxy().tokenMode(new OrderReq("T1")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TOKEN 未实现");
        assertThat(target.calls).as("业务不该被执行").hasValue(0);
    }

    @Test
    @DisplayName("占位时存储挂了 + fail-open=true：业务照跑，且不回填结果")
    void failOpenBypassesBusiness() {
        properties.getIdempotent().setFailOpen(true);
        storage.failAcquireFromCall = 1;

        OrderResult result = proxy().submit(new OrderReq("B1"));

        assertThat(result).isEqualTo(new OrderResult("OK-B1"));
        assertThat(target.calls).hasValue(1);
        // 关键：BYPASS 不能回填。回填了就等于"没做幂等却留下了幂等记录"，
        // 下一次请求会读到一条来自非幂等执行的 DONE
        assertThat(storage.delegate.getResult(keyOf("B1"))).isEmpty();
    }

    @Test
    @DisplayName("占位时存储挂了 + fail-open=false：抛 GuardianUnavailableException，业务不执行")
    void failClosedRethrows() {
        properties.getIdempotent().setFailOpen(false);
        storage.failAcquireFromCall = 1;

        assertThatThrownBy(() -> proxy().submit(new OrderReq("B2")))
                .isInstanceOf(GuardianUnavailableException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("读取时存储挂了 + fail-open=true：当作无记录，重试占位后放行")
    void readFailOpenRetriesThenBypasses() {
        properties.getIdempotent().setFailOpen(true);
        storage.seed(keyOf("B3"), IdempotentAspect.PENDING);
        // 第 1 次 setIfAbsent 正常返回 false（已存在）→ 读失败 → 重试占位时第 2 次抛
        storage.failAcquireFromCall = 2;
        storage.failGet = true;

        OrderResult result = proxy().submit(new OrderReq("B3"));

        assertThat(result).isEqualTo(new OrderResult("OK-B3"));
        assertThat(target.calls).hasValue(1);
    }

    @Test
    @DisplayName("读取时存储挂了 + fail-open=false：抛 GuardianUnavailableException")
    void readFailClosedRethrows() {
        properties.getIdempotent().setFailOpen(false);
        storage.seed(keyOf("B4"), IdempotentAspect.PENDING);
        storage.failGet = true;

        assertThatThrownBy(() -> proxy().submit(new OrderReq("B4")))
                .isInstanceOf(GuardianUnavailableException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("占位失败但读不到值 + key 还在：重试占位仍是重复 → 拒绝")
    void hiddenExistingKeyTreatedAsDuplicate() {
        storage.seed(keyOf("B5"), IdempotentAspect.PENDING);
        storage.hideExisting = true;

        assertThatThrownBy(() -> proxy().submit(new OrderReq("B5")))
                .isInstanceOf(DuplicateRequestException.class);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("占位与读取之间 key 过期：重试占位拿到首次，必须执行业务")
    void expiredBetweenAcquireAndReadExecutesBusiness() {
        storage.seed(keyOf("B6"), IdempotentAspect.PENDING);
        // 读的瞬间 key 消失（模拟过期窗口），重试占位应拿到 FIRST
        storage.deleteOnGet = true;

        OrderResult result = proxy().submit(new OrderReq("B6"));

        assertThat(result).isEqualTo(new OrderResult("OK-B6"));
        assertThat(target.calls).as("没人持有这个 key，不该判重复").hasValue(1);
    }

    @Test
    @DisplayName("key 内容不是本框架格式：按重复请求处理，不覆盖也不执行")
    void unknownPlaceholderTreatedAsDuplicate() {
        storage.seed(keyOf("B7"), "some-other-app-wrote-this");

        assertThatThrownBy(() -> proxy().submit(new OrderReq("B7")))
                .isInstanceOf(DuplicateRequestException.class);
        assertThat(target.calls).hasValue(0);
    }

    // ---- 用例：PENDING 三态 ----

    @Test
    @DisplayName("进行中 + onPending=FAIL：直接拒绝（默认行为）")
    void pendingWithFailStrategyRejects() {
        storage.seed(keyOf("P1"), IdempotentAspect.PENDING);

        assertThatThrownBy(() -> proxy().submit(new OrderReq("P1")))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessage("请勿重复提交");
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("进行中 + onPending=SPIN：等到 DONE 后返回缓存结果，不重复执行")
    void pendingWithSpinReturnsCachedResult() throws Exception {
        String key = keyOf("P2");
        storage.seed(key, IdempotentAspect.PENDING);

        // 另一个线程模拟"首次请求跑完并回填"
        Thread writer = new Thread(() -> {
            sleep(80);
            storage.complete(key, new JacksonResultCodec(null).serialize(new OrderResult("CACHED")));
        });
        writer.start();

        OrderResult result = proxy().spinLong(new OrderReq("P2"));
        writer.join();

        assertThat(result).as("自旋等到的是别人写的结果，不是自己执行").isEqualTo(new OrderResult("CACHED"));
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("进行中 + onPending=SPIN 超时：仍然拒绝")
    void pendingWithSpinTimesOut() {
        storage.seed(keyOf("P3"), IdempotentAspect.PENDING);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> proxy().spinShort(new OrderReq("P3")))
                .isInstanceOf(DuplicateRequestException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed)
                .as("应当真的等满了 pendingSpinMillis 才放弃（而不是第一次读到 PENDING 就立刻拒绝）")
                .isGreaterThanOrEqualTo(40L)
                .as("也不能等成无限重试——上限是 pendingSpinMillis，不是 3s 的长预算")
                .isLessThan(2000L);
        assertThat(target.calls).hasValue(0);
    }

    @Test
    @DisplayName("自旋期间占位过期：立刻拒绝，不能放行（业务可能还在跑）")
    void pendingExpiresDuringSpinRejects() throws Exception {
        String key = keyOf("P4");
        storage.seed(key, IdempotentAspect.PENDING);

        Thread dropper = new Thread(() -> {
            sleep(50);
            storage.delegate.releaseIfMatch(key, IdempotentAspect.PENDING);
        });
        dropper.start();

        assertThatThrownBy(() -> proxy().spinLong(new OrderReq("P4")))
                .isInstanceOf(DuplicateRequestException.class);
        dropper.join();

        assertThat(target.calls).as("放行会导致重复执行").hasValue(0);
    }

    @Test
    @DisplayName("自旋期间被中断：恢复中断标志并拒绝，不能吞掉中断")
    void pendingSpinInterruptedRejects() throws Exception {
        storage.seed(keyOf("P5"), IdempotentAspect.PENDING);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptFlagAfter = new AtomicBoolean();

        Thread spinner = new Thread(() -> {
            try {
                proxy().spinLong(new OrderReq("P5"));
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                // 在同一个线程里读，否则读到的是主线程的中断状态
                interruptFlagAfter.set(Thread.currentThread().isInterrupted());
            }
        });
        spinner.start();
        sleep(80);
        spinner.interrupt();
        spinner.join(3000);

        assertThat(spinner.isAlive()).as("中断后应当立即退出自旋").isFalse();
        assertThat(thrown.get()).isInstanceOf(DuplicateRequestException.class);
        assertThat(interruptFlagAfter.get())
                .as("必须把中断标志恢复回去，否则上层线程池回收线程时中断会丢")
                .isTrue();
        assertThat(target.calls).hasValue(0);
    }

    // ---- 用例：异常语义 ----

    @Test
    @DisplayName("锁获取失败 + onException=KEEP：仍然必须释放占位（业务从未开始）")
    void lockFailureReleasesDespiteKeepPolicy() {
        assertThatThrownBy(() -> proxy().lockFailed(new OrderReq("E1")))
                .isInstanceOf(LockAcquireFailedException.class);

        assertThat(storage.delegate.getResult(keyOf("E1")))
                .as("业务从未执行，留着占位会让用户立刻重试被封 10 分钟")
                .isEmpty();
    }

    @Test
    @DisplayName("业务异常 + RELEASE：占位释放，可立即重试")
    void businessFailureReleases() {
        OrderService proxy = proxy();
        assertThatThrownBy(() -> proxy.businessFailed(new OrderReq("E2")))
                .isInstanceOf(BusinessException.class);
        assertThat(storage.delegate.getResult(keyOf("E2"))).isEmpty();

        // 重试能真正执行
        assertThatThrownBy(() -> proxy.businessFailed(new OrderReq("E2")))
                .isInstanceOf(BusinessException.class);
        assertThat(target.calls).hasValue(2);
    }

    @Test
    @DisplayName("释放失败：不能覆盖业务原本的异常，占位由 ttl 兜底")
    void releaseFailureDoesNotMaskBusinessException() {
        storage.failRelease = true;

        assertThatThrownBy(() -> proxy().businessFailed(new OrderReq("E3")))
                .as("用户要看到的是业务异常，不是释放时的网络异常")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务失败 E3");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
