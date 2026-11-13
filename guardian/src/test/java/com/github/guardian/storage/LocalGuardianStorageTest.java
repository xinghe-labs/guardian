package com.github.guardian.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalGuardianStorage} 的行为测试。
 *
 * <p>用可控时钟验证过期，不靠 {@code Thread.sleep} —— 睡眠会让测试变慢且不稳定。
 */
class LocalGuardianStorageTest {

    /** 可手动推进的时钟，避免测试里真的等 ttl。 */
    static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2024-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LocalGuardianStorage storage = new LocalGuardianStorage(clock);

    @Test
    @DisplayName("setIfAbsent：首次成功，第二次失败")
    void setIfAbsent_firstWinsSecondLoses() {
        assertThat(storage.setIfAbsent("k", "PENDING", Duration.ofSeconds(10))).isTrue();
        assertThat(storage.setIfAbsent("k", "PENDING", Duration.ofSeconds(10))).isFalse();
    }

    @Test
    @DisplayName("setIfAbsent：ttl 过期后可以重新占位")
    void setIfAbsent_afterExpiry_allowsAgain() {
        assertThat(storage.setIfAbsent("k", "v1", Duration.ofSeconds(10))).isTrue();

        clock.advance(Duration.ofSeconds(10)); // 恰好到期即视为过期

        assertThat(storage.setIfAbsent("k", "v2", Duration.ofSeconds(10))).isTrue();
        assertThat(storage.getResult("k")).contains("v2");
    }

    @Test
    @DisplayName("releaseIfMatch：值不匹配时不删，匹配时才删（防误删他人锁）")
    void releaseIfMatch_onlyOwnLock() {
        storage.setIfAbsent("lock", "owner-A", Duration.ofSeconds(30));

        // 线程 A 超时后锁已属于 B，A 的 finally 拿着旧 value 来删 —— 必须删不掉
        assertThat(storage.releaseIfMatch("lock", "owner-B")).isFalse();
        assertThat(storage.getResult("lock")).contains("owner-A");

        assertThat(storage.releaseIfMatch("lock", "owner-A")).isTrue();
        assertThat(storage.getResult("lock")).isEmpty();
    }

    @Test
    @DisplayName("releaseIfMatch：key 已过期时返回 false")
    void releaseIfMatch_expired_returnsFalse() {
        storage.setIfAbsent("lock", "owner-A", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));

        assertThat(storage.releaseIfMatch("lock", "owner-A")).isFalse();
    }

    @Test
    @DisplayName("getResult：不存在为空，过期后也为空")
    void getResult_emptyWhenMissingOrExpired() {
        assertThat(storage.getResult("nope")).isEmpty();

        storage.setResult("k", "DONE:{}", Duration.ofSeconds(5));
        assertThat(storage.getResult("k")).contains("DONE:{}");

        clock.advance(Duration.ofSeconds(5));
        assertThat(storage.getResult("k")).isEmpty();
    }

    @Test
    @DisplayName("setResult：能覆盖 PENDING 写回 DONE")
    void setResult_overwritesPending() {
        storage.setIfAbsent("k", "PENDING", Duration.ofSeconds(10));
        storage.setResult("k", "DONE:{\"v\":1}", Duration.ofSeconds(10));

        assertThat(storage.getResult("k")).contains("DONE:{\"v\":1}");
    }

    @Test
    @DisplayName("incrementInWindow：窗口内累计递增，第 11 次返回 11（调用方据此拒绝）")
    void incrementInWindow_countsWithinWindow() {
        Duration window = Duration.ofSeconds(1);
        long now = clock.millis();

        for (int i = 1; i <= 10; i++) {
            assertThat(storage.incrementInWindow("rl", window, 10, now)).isEqualTo(i);
        }
        // 第 11 次返回 11 > permits(10)，切面据此拒绝
        assertThat(storage.incrementInWindow("rl", window, 10, now)).isEqualTo(11);
    }

    @Test
    @DisplayName("incrementInWindow：被拒绝的请求不占用配额（否则重试会把自己越挤越深）")
    void incrementInWindow_rejectedRequestsAreNotRecorded() {
        Duration window = Duration.ofSeconds(1);
        long now = clock.millis();

        assertThat(storage.incrementInWindow("rl", window, 3, now)).isEqualTo(1);
        assertThat(storage.incrementInWindow("rl", window, 3, now)).isEqualTo(2);
        assertThat(storage.incrementInWindow("rl", window, 3, now)).isEqualTo(3);

        // 第 4、5 次都返回 4（不写入，所以计数停在 3）——
        // 若把拒绝的请求也写入，第 5 次会返回 5，配额被自己的重试吃掉
        assertThat(storage.incrementInWindow("rl", window, 3, now)).isEqualTo(4);
        assertThat(storage.incrementInWindow("rl", window, 3, now)).isEqualTo(4);
    }

    @Test
    @DisplayName("incrementInWindow：滑出窗口后计数归零重算")
    void incrementInWindow_slidesOut() {
        Duration window = Duration.ofSeconds(1);
        long t0 = clock.millis();

        storage.incrementInWindow("rl", window, 100, t0);
        storage.incrementInWindow("rl", window, 100, t0);
        assertThat(storage.incrementInWindow("rl", window, 100, t0)).isEqualTo(3);

        // 窗口左开右闭 (cutoff, now]：t0 的 3 条在 t0+1000 时全部滑出
        assertThat(storage.incrementInWindow("rl", window, 100, t0 + 1000)).isEqualTo(1);
    }

    @Test
    @DisplayName("incrementInWindow：恰好落在窗口左边界的记录算过期")
    void incrementInWindow_boundaryExclusive() {
        Duration window = Duration.ofSeconds(1);
        long t0 = 1_000L;

        storage.incrementInWindow("rl", window, 100, t0);
        // cutoff = 1000 - 1000 = 0，t0=1000 不在 cutoff 上；换一个精确落在边界的
        storage.incrementInWindow("rl", window, 100, 2_000L);
        // 此时 cutoff = 1000，t0=1000 <= 1000 应被剔除，只剩 1 条
        assertThat(storage.incrementInWindow("rl", window, 100, 2_000L)).isEqualTo(2);
    }

    @Test
    @DisplayName("renew：值匹配才续期，续期后超过原 ttl 仍存活")
    void renew_extendsOnlyForOwner() {
        storage.setIfAbsent("lock", "owner-A", Duration.ofSeconds(5));

        clock.advance(Duration.ofSeconds(4));
        assertThat(storage.renew("lock", "owner-B", Duration.ofSeconds(5))).isFalse();

        assertThat(storage.renew("lock", "owner-A", Duration.ofSeconds(5))).isTrue();

        // 原 ttl 是 5s，已过 4s；再走 4s 就到原到期点之后，但续期把它推到了 4+5=9s
        clock.advance(Duration.ofSeconds(4));
        assertThat(storage.getResult("lock")).contains("owner-A");
    }

    @Test
    @DisplayName("renew：锁已过期时续期失败（锁已丢失）")
    void renew_afterExpiry_fails() {
        storage.setIfAbsent("lock", "owner-A", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));

        assertThat(storage.renew("lock", "owner-A", Duration.ofSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("purgeExpired：清理过期记录")
    void purgeExpired_removesStaleEntries() {
        storage.setIfAbsent("k1", "v", Duration.ofSeconds(1));
        storage.setResult("k2", "v", Duration.ofSeconds(100));
        clock.advance(Duration.ofSeconds(2));

        storage.purgeExpired();

        assertThat(storage.size()).isEqualTo(1);
        assertThat(storage.getResult("k2")).contains("v");
    }

    @Test
    @DisplayName("并发：100 线程抢同一把锁，只有 1 个成功")
    void setIfAbsent_concurrent_onlyOneWins() throws Exception {
        int threads = 100;
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (storage.setIfAbsent("hot-lock", "v", Duration.ofSeconds(30))) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发：100 线程同时计数，返回值恰好是 1..100 无重复（证明计数原子）")
    void incrementInWindow_concurrent_noLostUpdate() throws Exception {
        int threads = 100;
        Duration window = Duration.ofMinutes(1);
        long now = clock.millis();
        List<Long> counts = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        counts.add(storage.incrementInWindow("hot-rl", window, 1000, now));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 若计数不是原子的，会出现重复值（两个线程读到同一个 size）
        Set<Long> distinct = new HashSet<>(counts);
        assertThat(distinct).hasSize(threads);
        assertThat(distinct).contains(1L, (long) threads);
    }
}
