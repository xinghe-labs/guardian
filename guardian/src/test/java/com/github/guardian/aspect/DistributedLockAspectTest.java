package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.exception.LockLostException;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.KeyResolver;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
 * 分布式锁切面测试。
 *
 * <p>验收条目在这里逐条落地：100 线程抢锁成功数=1、看门狗续期让长业务不被抢、
 * 锁丢失后抛 {@code LockLostException}、释放时不删别人的锁。
 */
class DistributedLockAspectTest extends RedisAspectTestBase {

    private GuardianProperties properties;
    private Watchdog watchdog;
    private LockService proxy;

    // ---- 测试夹具 ----

    static class LockService {

        /** 业务进入时计数，让测试能在“切面已经调过 watchdog.watch()”之后再动手。 */
        static volatile CountDownLatch entered;

        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 2, unit = TimeUnit.SECONDS)
        String quick(String id) {
            return "ok";
        }

        /** 业务跑 500ms，用来制造"锁被持有"的窗口。 */
        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 2, unit = TimeUnit.SECONDS)
        String hold(String id) throws InterruptedException {
            Thread.sleep(500L);
            return "held";
        }

        static volatile CountDownLatch holdEntered;
        static volatile CountDownLatch holdRelease;

        /**
         * 进来后挂住，直到测试主动放行。租期 10s 保证测试期间不会自己过期。
         *
         * <p>为什么不用 {@link #hold}：那个靠 {@code Thread.sleep(500)} 制造窗口，
         * 测试必须猜"多少毫秒后另一线程肯定已经抢到锁了"。机器一忙（比如前面刚起过
         * 100 线程）这个猜测就不成立，用例会间歇性失败。用 latch 就是精确同步：
         * 业务进来了 = 锁一定已经拿到。
         */
        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 10, unit = TimeUnit.SECONDS)
        String holdBlocking(String id) throws InterruptedException {
            CountDownLatch e = holdEntered;
            if (e != null) {
                e.countDown();
            }
            CountDownLatch r = holdRelease;
            if (r != null) {
                r.await(10, TimeUnit.SECONDS);
            }
            return "held";
        }

        /** 业务跑 3s，租期只有 1s —— 不靠看门狗必被抢。 */
        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 1, unit = TimeUnit.SECONDS)
        String longRunning(String id) throws InterruptedException {
            CountDownLatch latch = entered;
            if (latch != null) {
                latch.countDown();
            }
            Thread.sleep(3000L);
            return "long-ok";
        }

        /** 业务跑 1.5s，期间主线程会把 key 删掉，模拟"锁丢了"。 */
        @DistributedLock(key = "#id", waitTime = 0, leaseTime = 1, unit = TimeUnit.SECONDS)
        String willLoseLock(String id) throws InterruptedException {
            Thread.sleep(1500L);
            return "should-not-return";
        }

        @DistributedLock(key = "#id", waitTime = 1, leaseTime = 5, unit = TimeUnit.SECONDS)
        String waitable(String id) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        properties = new GuardianProperties();
        properties.setKeyPrefix(prefix);
        watchdog = new Watchdog(storage);
        proxy = proxyFor(storage);
    }

    @AfterEach
    void stopWatchdog() {
        watchdog.destroy();
    }

    private LockService proxyFor(GuardianStorage backing) {
        DistributedLockAspect aspect = new DistributedLockAspect(backing, properties,
                new KeyResolver(), new GuardianKeys(properties.getKeyPrefix()), watchdog);
        AspectJProxyFactory factory = new AspectJProxyFactory(new LockService());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    // ---- 用例 ----

    @Test
    @DisplayName("100 线程同时抢锁，成功数恰好为 1")
    void onlyOneOfHundredWins() throws Exception {
        int threads = 100;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await(5, TimeUnit.SECONDS);
                        proxy.hold("race");
                        successes.incrementAndGet();
                    } catch (LockAcquireFailedException e) {
                        rejections.incrementAndGet();
                    } catch (Exception e) {
                        // 其他异常说明测试环境有问题，不能算进通过数
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 业务持锁 500ms，100 个线程在屏障处同时出发（毫秒级内到齐），
        // 因此只有抢到的那一个能进去，其余 99 个 waitTime=0 只试一次就放弃
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("锁被持有时拿不到，且抛的是 LockAcquireFailedException（带注解 message）")
    void failsWhenAlreadyHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LockService.holdEntered = entered;
        LockService.holdRelease = release;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.holdBlocking("busy"));
            // 不再 sleep 猜时间：业务方法进入 = 切面已经拿到锁，这是确定的
            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("持锁线程应当在 5s 内进入业务方法")
                    .isTrue();

            assertThatThrownBy(() -> proxy.quick("busy"))
                    .isInstanceOf(LockAcquireFailedException.class)
                    .hasMessage("操作正在进行，请稍后");

            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
        } finally {
            release.countDown();
            pool.shutdownNow();
            LockService.holdEntered = null;
            LockService.holdRelease = null;
        }
    }

    /**
     * 轮询到锁 key 真的出现在存储里，最多 5s。
     *
     * <p>用来替掉原来的 {@code Thread.sleep(150)} —— 那种写法是在赌"另一线程已经
     * 抢到锁了"，而抢锁的延迟取决于机器负载。用状态观察就变成确定的。
     */
    private void awaitLockHeld(String id) throws InterruptedException {
        String lockKey = new GuardianKeys(prefix).lock(id);
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (storage.getResult(lockKey).isPresent()) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("等了 5s，锁 " + lockKey + " 仍未出现——持锁线程没起来？");
    }

    @Test
    @DisplayName("waitTime 内能等到前一个释放（轮询重试生效）")
    void waitsForRelease() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.hold("w"));
            awaitLockHeld("w");

            // 持锁方还要 ~350ms 才释放；等待 1s 足够
            long start = System.nanoTime();
            assertThat(proxy.waitable("w")).isEqualTo("ok");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMs).isGreaterThanOrEqualTo(100L);
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("看门狗续期：租期 1s、业务跑 3s，锁始终不被抢")
    void watchdogKeepsLeaseAlive() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.longRunning("dog"));
            // 1.5s 时早已超过 1s 租期，若没有续期锁必然已过期
            Thread.sleep(1500L);

            assertThatThrownBy(() -> proxy.quick("dog"))
                    .as("看门狗没有续上的话，这里会拿到锁")
                    .isInstanceOf(LockAcquireFailedException.class);

            // 业务正常返回，说明续期成功、没有误判 lost
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("long-ok");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("锁丢失：key 被外部删除，续期连续失败后抛 LockLostException")
    void throwsLockLostWhenKeyDisappears() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.willLoseLock("gone"));
            awaitLockHeld("gone");

            // 模拟锁过期 / 被驱逐 / 被别人接管
            template.delete(prefix + "lock:gone");

            // 业务还要再跑 1.3s，其间续期会连续失败（间隔 = 1s/3 ≈ 333ms），
            // 返回时切面必须判定为丢失
            assertThatThrownBy(() -> holder.get(10, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOf(LockLostException.class)
                    .hasMessageContaining("锁在执行期间已丢失");

            assertThat(watchdog.lostCount()).isGreaterThanOrEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("释放时不删别人的锁：value 不匹配就放弃（经典 bug：A 的锁过期后 B 拿到锁，A 释放时误删 B 的锁）")
    void doesNotDeleteOthersLock() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.hold("mine"));
            awaitLockHeld("mine");

            // 模拟"我的锁过期了，别人拿到了"——把 value 换成另一个持有者
            String lockKey = prefix + "lock:mine";
            template.opsForValue().set(lockKey, "someone-else");

            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");

            // 切面释放时比对 value 失败，绝不能把别人的锁删掉
            assertThat(template.opsForValue().get(lockKey)).isEqualTo("someone-else");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("边界：看门狗被停掉时不抛 LockLostException（续期不再运行 = 也发现不了丢失）")
    void stoppingWatchdogDoesNotDetectLoss() throws Exception {
        // 这条用例记录的是一份<b>已知边界</b>，不是期望行为。
        // 直觉预期是“手动停掉续期调度器，业务在租期到期后抛 LockLostException”—— 实测做不到：调度器停了就没有任何续期任务在跑，
        // 也就没有任何人能把 consecutiveFailures 加上去，lost 永远是 false。
        // 后果是业务正常返回、锁早已过期（只在 releaseIfMatch 失败时留一条 warn）。
        // 真正的“锁丢失”检测依赖续期<b>仍然在跑且失败</b>（见上一条用例）。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch inBusiness = new CountDownLatch(1);
        LockService.entered = inBusiness;
        try {
            Future<String> holder = pool.submit(() -> proxy.longRunning("stop"));
            // 等到业务方法真的进去了才停调度器：此时切面已经调过 watch()，
            // 不会因为“还没排上任务就被 shutdown” 拿一个 RejectedExecutionException
            assertThat(inBusiness.await(5, TimeUnit.SECONDS)).isTrue();
            watchdog.destroy();

            // 业务跑 3s，租期 1s，早就过期了 —— 但切面感知不到
            assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("long-ok");
            assertThat(watchdog.lostCount()).isZero();
        } finally {
            LockService.entered = null;
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Redis 不可用 + 默认 fail-closed：拿不到锁就当没拿到，不能假装拿到")
    void failClosedByDefault() {
        // 注意：锁的 fail-open 默认是 false，与限流的 true <b>故意不同</b>。
        // 理由：限流失效只是短时过载，全站 502 更贵；
        // 而拿不到锁就放行 = 锁根本不存在，两个节点会同时改同一份数据。
        // 我第一版测试就是在这里写错了假设（以为和限流一样默认放行）。
        GuardianStorage broken = mock(GuardianStorage.class);
        when(broken.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new GuardianUnavailableException("redis down"));

        assertThatThrownBy(() -> proxyFor(broken).quick("x"))
                .isInstanceOf(GuardianUnavailableException.class)
                .hasMessage("redis down");
    }

    @Test
    @DisplayName("Redis 不可用 + 显式开 fail-open：无锁执行（放弃互斥，保可用性）")
    void failOpenProceedsWithoutLock() {
        properties.getLock().setFailOpen(true);
        GuardianStorage broken = mock(GuardianStorage.class);
        when(broken.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new GuardianUnavailableException("redis down"));

        assertThat(proxyFor(broken).quick("x")).isEqualTo("ok");
    }
}
