package com.github.guardian.lock;

import com.github.guardian.storage.GuardianStorage;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * 看门狗：在业务执行期间周期性续期租约，避免"业务还没跑完锁就过期了"。
 *
 * <p><b>调度模型：一个单线程 {@code ScheduledExecutorService} 管所有锁</b>，
 * 每把锁一个 {@link ScheduledFuture}，任务体只做一次 {@code PEXPIRE}（微秒级）。
 * 不是"一锁一线程"——那样 1 万把锁就是 1 万个线程。
 *
 * <p>上限在哪：单线程若某次续期被阻塞（Redis 慢查询、网络抖），会<b>串行推迟</b>后续所有续期。
 * 缓解靠两点：① 续期间隔设为租期的 1/3，留 2 次容错；② 某把锁续期失败立即计数，
 * 连续失败就立刻置位，不拖到下个周期才处理。
 *
 * <p>与 Redisson 的对比：Redisson 用 {@code HashedWheelTimer}，本质也是单线程调度；
 * 这里用 JDK 自带的 {@code ScheduledExecutorService}，省一个依赖，代价是没有时间轮那么高的
 * 槽位密度（时间轮 O(1) 定位，这里是堆排序 O(log n)）。锁数量在万级以内两者都够。
 */
public class Watchdog implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    /**
     * 连续失败多少次判定锁已丢失。
     *
     * <p>取 2 而不是 1：一次 Redis 抖动不该中断一个正常的业务操作。
     * 而续期间隔是租期的 1/3，所以连续 2 次失败意味着已过约 2/3 租期仍未续上——
     * 此时判断"锁大概率保不住"是合理的，再等一次就真的过期了。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 2;

    private final GuardianStorage storage;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong lostCount = new AtomicLong();

    public Watchdog(GuardianStorage storage) {
        this.storage = storage;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "guardian-watchdog");
            // daemon：应用关闭时不阻止 JVM 退出（destroy() 里还会主动 shutdownNow）
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 开始为一把锁续期。
     *
     * @param renewInterval 实际续期间隔，调用方应传
     *                      {@code properties.getLock().effectiveRenewInterval(lease)}
     */
    public Handle watch(String key, String value, Duration lease, Duration renewInterval) {
        Handle handle = new Handle(key, value);
        long intervalMs = Math.max(1L, renewInterval.toMillis());
        handle.future = scheduler.scheduleAtFixedRate(
                () -> renew(handle, lease), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return handle;
    }

    private void renew(Handle handle, Duration lease) {
        if (handle.lost) {
            return;
        }
        try {
            if (storage.renew(handle.key, handle.value, lease)) {
                handle.consecutiveFailures = 0;
                return;
            }
            handle.consecutiveFailures++;
            log.warn("锁续期未成功（可能已不属于自己）: key={}, 连续失败 {}/{}",
                    handle.key, handle.consecutiveFailures, MAX_CONSECUTIVE_FAILURES);
        } catch (RuntimeException e) {
            handle.consecutiveFailures++;
            log.warn("锁续期异常: key={}, 连续失败 {}/{}",
                    handle.key, handle.consecutiveFailures, MAX_CONSECUTIVE_FAILURES, e);
        }

        if (handle.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            handle.lost = true;
            lostCount.incrementAndGet();
            // 只能"事后阻断"：Java 没有可靠手段从续期线程打断业务线程。
            // Thread.interrupt() 对卡在 JDBC 查询或纯 CPU 循环的业务无效，
            // Thread.stop() 已废弃且会撕裂事务。所以这里只置标记 + 记日志，
            // 由切面在业务返回前检查并抛 LockLostException。
            log.error("锁已丢失（连续 {} 次续期失败），业务返回时将被判定为失败: key={}",
                    MAX_CONSECUTIVE_FAILURES, handle.key);
            handle.cancel();
        }
    }

    /** 累计判定为"锁丢失"的次数，可接 Micrometer 的 {@code guardian.lock.lost} 指标。 */
    public long lostCount() {
        return lostCount.get();
    }

    @Override
    public void destroy() {
        // 不 shutdown 的话 ScheduledExecutorService 会泄漏
        scheduler.shutdownNow();
    }

    /**
     * 一把锁的续期句柄。
     *
     * <p><b>{@code lost} 必须是句柄上的 volatile 字段，不能用 {@code ThreadLocal}</b>：
     * 续期跑在 watchdog 调度线程，业务跑在业务线程，两者不是同一个线程，
     * {@code ThreadLocal} 写进去业务线程根本读不到。而且调度线程是所有锁共用的一个，
     * 用 {@code ThreadLocal} 还会互相覆盖。
     */
    public static final class Handle {

        private final String key;
        private final String value;
        private volatile ScheduledFuture<?> future;
        private volatile int consecutiveFailures;
        private volatile boolean lost;

        Handle(String key, String value) {
            this.key = key;
            this.value = value;
        }

        /** 业务返回前必须检查：为 {@code true} 说明租期内没续上，结果不可信。 */
        public boolean isLost() {
            return lost;
        }

        public void cancel() {
            ScheduledFuture<?> current = future;
            if (current != null) {
                // false = 不打断正在执行的那一次续期，避免留下半截状态
                current.cancel(false);
            }
        }
    }
}
