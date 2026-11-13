package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.annotation.Idempotent;
import com.github.guardian.annotation.RateLimit;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.exception.GuardianException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.exception.RateLimitExceededException;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * S5 并发验收：50 线程同 key，完整链路（代理 → 切面 → Redis 实现 → 真实 Redis）。
 *
 * <p><b>为什么这些不能只靠 storage 层单测</b>：storage 单测已经证明过 Lua 原子性
 * （窗口边界、CAS 释放），但"切面在并发下不会额外丢一步"是另一回事 ——
 * 比如 SpEL 解析出同一个 key、占位与回填之间没有竞态窗口。这里要的是端到端。
 *
 * <p><b>断言为什么是精确计数而不是"大于/小于"</b>：S4 变异测试的教训 ——
 * {@code isGreaterThan(5)} 对正确实现和变异实现都成立。50 线程限流 permits=10，
 * 正确答案是恰好 10 过 45 拒；写"超过 8 个通过"就等于没测超发。
 *
 * <p><b>为什么结论是确定性的</b>：{@code SET NX} 原子（恰好一个赢家）、
 * Lua 给每个请求分配唯一序号（member 含 UUID，恰好 permits 个通过）、
 * 幂等占位唯一（其余全部落在 PENDING 或 DONE 两态）。胜负与线程调度顺序无关 ——
 * 换任何一台机器跑，通过数都一样。起跑用 {@link CountDownLatch} 对齐，
 * 让 50 个请求真正重叠（不对齐的话前面跑完了后面才进来，测的是串行不是并发）。
 */
class ConcurrencyTest extends RedisAspectTestBase {

    private static final int THREADS = 50;

    private GuardianProperties properties;
    private Watchdog watchdog;

    // ---- 测试夹具 ----

    static class OrderService {
        final AtomicInteger calls = new AtomicInteger();

        record Order(String orderNo) {
        }

        record Result(String status) {
        }

        /**
         * 业务里 sleep 一小段：让"首次在跑"的窗口足够宽，后面进来的线程
         * 大概率落在 PENDING（抛 {@link DuplicateRequestException}）而不是
         * DONE（拿到缓存结果）。两种落点都合法，{@code calls == 1} 才是硬断言。
         */
        @Idempotent(key = "#order.orderNo", cacheResult = true)
        Result submit(Order order) throws InterruptedException {
            calls.incrementAndGet();
            Thread.sleep(100L);
            return new Result("OK-" + order.orderNo());
        }
    }

    static class LimitService {
        static final AtomicInteger passed = new AtomicInteger();

        @RateLimit(key = "'global'", permits = 10, window = 30, unit = TimeUnit.SECONDS)
        String call() {
            passed.incrementAndGet();
            return "passed";
        }
    }

    static class LockService {
        static final AtomicInteger entered = new AtomicInteger();

        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 10, unit = TimeUnit.SECONDS)
        String run(String id) throws InterruptedException {
            entered.incrementAndGet();
            // 持有 500ms：让 50 个线程的全部到达窗口落在持有期内。
            // 不加的话赢家秒进秒出，释放后到达的线程照样拿锁 ——
            // 赢家数取决于线程调度，用例变成抽奖（实测出现过 3 个赢家，
            // 但那不是被测代码的 bug，是测试没把“并发”真造出来）。
            Thread.sleep(500L);
            return "winner";
        }
    }

    @BeforeEach
    void buildAspects() {
        properties = new GuardianProperties();
        watchdog = new Watchdog(storage);
        LimitService.passed.set(0);
        LockService.entered.set(0);
    }

    @AfterEach
    void stopWatchdog() {
        watchdog.destroy();
    }

    private OrderService orderProxy(OrderService target) {
        AspectJProxyFactory f = new AspectJProxyFactory(target);
        f.setProxyTargetClass(true);
        f.addAspect(new IdempotentAspect(storage, properties, new KeyResolver(),
                new GuardianKeys(prefix), new JacksonResultCodec(null)));
        return f.getProxy();
    }

    private LimitService limitProxy() {
        AspectJProxyFactory f = new AspectJProxyFactory(new LimitService());
        f.setProxyTargetClass(true);
        f.addAspect(new RateLimitAspect(storage, properties, new KeyResolver(),
                new GuardianKeys(prefix)));
        return f.getProxy();
    }

    private LockService lockProxy() {
        AspectJProxyFactory f = new AspectJProxyFactory(new LockService());
        f.setProxyTargetClass(true);
        f.addAspect(new DistributedLockAspect(storage, properties, new KeyResolver(),
                new GuardianKeys(prefix), watchdog));
        return f.getProxy();
    }

    /** 50 线程对齐起跑，返回每线程的结果或异常。任何非预期异常直接留在 Future 里炸出来。 */
    private <T> List<Object> run50(Callable<T> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return action.call();
                    } catch (GuardianException e) {
                        // 框架语义内的拒绝也走这里（Duplicate / RateLimitExceeded / LockAcquireFailed）
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("50 线程同 key 幂等：业务只执行 1 次，其余全部被拒（拿到缓存或抛重复）")
    void idempotentExecutesExactlyOnce() throws Exception {
        OrderService target = new OrderService();
        OrderService proxy = orderProxy(target);
        OrderService.Order order = new OrderService.Order("C-" + UUID.randomUUID());

        List<Object> results = run50(() -> proxy.submit(order));

        // 断言必须落在 target 上：CGLIB 代理对象上同名字段是未初始化的 null，
        // 代理子类不执行父类的字段初始化器 —— 断言 proxy.calls 拿到的是 null 而不是计数器
        assertThat(target.calls).as("业务必须恰好执行一次").hasValue(1);
        long accepted = results.stream().filter(r -> r instanceof OrderService.Result).count();
        long rejected = results.stream().filter(r -> r instanceof DuplicateRequestException).count();
        assertThat(accepted + rejected)
                .as("50 个结果必须要么是业务结果、要么是重复拒绝，不能有第三个异常，实际: %s",
                        results.stream().filter(r -> !(r instanceof OrderService.Result)
                                && !(r instanceof DuplicateRequestException)).toList())
                .isEqualTo(THREADS);
        assertThat(accepted).as("至少有首次请求拿到真实结果").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("50 线程限流 permits=10：恰好 10 个通过、40 个被拒（不多不少）")
    void rateLimitAdmitsExactlyPermits() throws Exception {
        LimitService proxy = limitProxy();

        List<Object> results = run50(proxy::call);

        assertThat(LimitService.passed).as("进入业务的请求数").hasValue(10);
        long passedCount = results.stream().filter("passed"::equals).count();
        long rejected = results.stream().filter(r -> r instanceof RateLimitExceededException).count();
        assertThat(passedCount).as("恰好放行 permits 个").isEqualTo(10);
        assertThat(rejected).as("其余全部被限流拒绝").isEqualTo(40);
    }

    @Test
    @DisplayName("50 线程抢同一把锁（waitTime=0）：恰好 1 个赢家，其余立刻失败")
    void lockHasExactlyOneWinner() throws Exception {
        LockService proxy = lockProxy();
        String id = "C-" + UUID.randomUUID();

        List<Object> results = run50(() -> proxy.run(id));

        assertThat(LockService.entered).as("业务进入数").hasValue(1);
        long winners = results.stream().filter("winner"::equals).count();
        long losers = results.stream().filter(r -> r instanceof LockAcquireFailedException).count();
        assertThat(winners).as("恰好一个赢家").isEqualTo(1);
        assertThat(losers).as("其余 49 个拿锁失败").isEqualTo(THREADS - 1);
    }
}
