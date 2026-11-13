package com.github.guardian.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 存储 SPI 的<b>契约测试</b>：同一套断言，{@link LocalGuardianStorage} 与
 * {@link RedisGuardianStorage} 各跑一遍。
 *
 * <p>为什么需要它：两个实现各有自己的测试类，但<b>没有任何东西保证它们语义一致</b>。
 * 切面只认 {@link GuardianStorage} 接口，一旦本地实现和 Redis 实现在某个边界上
 * 悄悄分叉（比如窗口左边界算不算过期、ttl 是覆盖还是累加），换实现就会出现
 * "测试环境好好的、上线换了存储就出错"。契约测试是唯一能拦住这种分叉的东西。
 *
 * <p>断言只写 {@link GuardianStorage} javadoc 里明确承诺过的语义，不写实现细节。
 * 换句话说：<b>这些用例通过 = 这个实现有资格被插进切面</b>。
 *
 * <p>两个实现共有的两条硬要求，在这里各有一条并发用例守着：
 * <ul>
 *   <li>{@code setIfAbsent} 并发抢占时<b>有且只有一个</b>赢家（否则锁形同虚设）</li>
 *   <li>{@code incrementInWindow} 并发调用返回的序号<b>互不重复</b>（否则限流会超发）</li>
 * </ul>
 * 这两条就是"为什么不能 GET 再 SET / GET 再 INCR"的实测证据。
 */
abstract class GuardianStorageContractTest {

    /** 被测实现。 */
    protected abstract GuardianStorage storage();

    /** 每次调用返回一个全新 key，保证用例之间不互相污染（Redis 实现必须带随机前缀）。 */
    protected abstract String uniqueKey(String suffix);

    // ---- 占位语义 ----

    @Test
    @DisplayName("契约：setIfAbsent 首次为 true，同一 key 再次为 false")
    void setIfAbsentIsExclusive() {
        GuardianStorage s = storage();
        String key = uniqueKey("acquire");

        assertThat(s.setIfAbsent(key, "PENDING", Duration.ofSeconds(5))).isTrue();
        assertThat(s.setIfAbsent(key, "PENDING", Duration.ofSeconds(5)))
                .as("已存在的 key 必须抢不到，否则锁和幂等都失效")
                .isFalse();
    }

    @Test
    @DisplayName("契约：setIfAbsent 的 ttl 真的生效，过期后可以重新占位")
    void setIfAbsentHonoursTtl() throws Exception {
        GuardianStorage s = storage();
        String key = uniqueKey("ttl");

        assertThat(s.setIfAbsent(key, "PENDING", Duration.ofMillis(120))).isTrue();
        Thread.sleep(250L);

        assertThat(s.getResult(key)).as("ttl 没设上就是永不过期，进程崩溃后 key 永久锁死").isEmpty();
        assertThat(s.setIfAbsent(key, "PENDING", Duration.ofSeconds(5)))
                .as("过期后应当能重新占位")
                .isTrue();
    }

    // ---- 释放语义 ----

    @Test
    @DisplayName("契约：releaseIfMatch 只在值匹配时删除，不误删他人的锁")
    void releaseIfMatchChecksValue() {
        GuardianStorage s = storage();
        String key = uniqueKey("release");
        s.setIfAbsent(key, "owner-A", Duration.ofSeconds(5));

        assertThat(s.releaseIfMatch(key, "owner-B"))
                .as("值不匹配还删掉的话，GC 停顿后 A 会删掉 B 刚拿到的锁")
                .isFalse();
        assertThat(s.getResult(key)).contains("owner-A");

        assertThat(s.releaseIfMatch(key, "owner-A")).isTrue();
        assertThat(s.getResult(key)).isEmpty();
    }

    @Test
    @DisplayName("契约：releaseIfMatch 对不存在的 key 返回 false，不抛异常")
    void releaseIfMatchOnMissingKey() {
        assertThat(storage().releaseIfMatch(uniqueKey("missing"), "whatever")).isFalse();
    }

    // ---- 结果读写 ----

    @Test
    @DisplayName("契约：getResult 对不存在的 key 返回 empty（不是抛异常，也不是空串）")
    void getResultOnMissingKey() {
        Optional<String> got = storage().getResult(uniqueKey("absent"));
        assertThat(got).isEmpty();
    }

    @Test
    @DisplayName("契约：setResult 覆写已有值，且能读回")
    void setResultOverwrites() {
        GuardianStorage s = storage();
        String key = uniqueKey("overwrite");
        s.setIfAbsent(key, "PENDING", Duration.ofSeconds(5));

        assertThat(s.setResult(key, "DONE:{\"v\":1}", Duration.ofSeconds(5))).isTrue();
        assertThat(s.getResult(key)).contains("DONE:{\"v\":1}");
    }

    // ---- 滑动窗口 ----

    @Test
    @DisplayName("契约：incrementInWindow 前 permits 次放行，第 permits+1 次返回值大于 permits")
    void windowAllowsUpToPermits() {
        GuardianStorage s = storage();
        String key = uniqueKey("window");
        Duration window = Duration.ofSeconds(10);
        long now = System.currentTimeMillis();

        for (int i = 1; i <= 5; i++) {
            assertThat(s.incrementInWindow(key, window, 5, now))
                    .as("第 %d 次应当放行", i)
                    .isEqualTo(i);
        }
        assertThat(s.incrementInWindow(key, window, 5, now))
                .as("超过 permits 时必须返回精确的 permits+1；只断言 >5 的话，"
                        + "把被拒绝的请求也写进窗口（返回 26）同样能通过，测试等于没测")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("契约：被拒绝的请求不占用配额")
    void rejectedRequestsDoNotConsumeQuota() {
        GuardianStorage s = storage();
        String key = uniqueKey("window-reject");
        Duration window = Duration.ofSeconds(10);
        long now = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            s.incrementInWindow(key, window, 5, now);
        }
        // 连续打 20 次超限请求
        for (int i = 0; i < 20; i++) {
            s.incrementInWindow(key, window, 5, now);
        }

        // 窗口滑到下一个时间点后，计数应当只有 5 条（被拒绝的 20 次不占配额）
        long later = now + 500L;
        assertThat(s.incrementInWindow(key, window, 5, later))
                .as("被拒绝的请求若也写入，返回值会是 26（5 次合法 + 20 次被拒 + 本次）；"
                        + "精确断言 6 才能区分")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("契约：记录滑出窗口后重新放行")
    void windowSlides() {
        GuardianStorage s = storage();
        String key = uniqueKey("window-slide");
        Duration window = Duration.ofMillis(200);
        long now = System.currentTimeMillis();

        for (int i = 0; i < 3; i++) {
            s.incrementInWindow(key, window, 3, now);
        }
        assertThat(s.incrementInWindow(key, window, 3, now)).isGreaterThan(3L);

        // 时间推进超过窗口长度，旧记录全部滑出
        assertThat(s.incrementInWindow(key, window, 3, now + 400L))
                .as("滑出窗口的记录必须被清掉，否则窗口永不重置")
                .isEqualTo(1L);
    }

    // ---- 续期 ----

    @Test
    @DisplayName("契约：renew 只在值匹配时成功")
    void renewChecksValue() {
        GuardianStorage s = storage();
        String key = uniqueKey("renew");
        s.setIfAbsent(key, "owner-A", Duration.ofMillis(300));

        assertThat(s.renew(key, "owner-B", Duration.ofSeconds(5)))
                .as("值不匹配还能续期的话，A 的看门狗会替 B 续锁")
                .isFalse();
        assertThat(s.renew(key, "owner-A", Duration.ofSeconds(5))).isTrue();

        // 续期后原 ttl 已过，但 key 仍应在
        try {
            Thread.sleep(400L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(s.getResult(key)).as("续期必须真的延长了 ttl").contains("owner-A");
    }

    // ---- 原子性（契约里最重要的两条）----

    @Test
    @DisplayName("契约：并发 setIfAbsent 有且只有一个赢家")
    void concurrentSetIfAbsentHasSingleWinner() throws Exception {
        GuardianStorage s = storage();
        String key = uniqueKey("race-acquire");
        int threads = 50;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (s.setIfAbsent(key, "PENDING", Duration.ofSeconds(10))) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get())
                .as("多于 1 个赢家 = 锁失效、幂等失效；这是 GET 再 SET 与 SET NX 的区别")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("契约：并发 incrementInWindow 返回的序号互不重复")
    void concurrentWindowIncrementsAreUnique() throws Exception {
        GuardianStorage s = storage();
        String key = uniqueKey("race-window");
        int threads = 50;
        long now = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        List<Long> tooMany = new ArrayList<>();
        AtomicInteger returned = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long seq = s.incrementInWindow(key, Duration.ofSeconds(30), threads, now);
                        returned.incrementAndGet();
                        if (!seen.add(seq)) {
                            tooMany.add(seq);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(returned.get()).isEqualTo(threads);
        assertThat(tooMany)
                .as("序号重复 = 计数不原子（GET 再 INCR），高并发下必然超发")
                .isEmpty();
        assertThat(seen).hasSize(threads);
    }
}
