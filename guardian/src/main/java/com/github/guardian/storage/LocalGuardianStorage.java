package com.github.guardian.storage;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 纯内存实现，用于单机、单元测试、以及没有 Redis 的演示环境。
 *
 * <p><b>它不能替代 Redis 实现</b>：进程内状态，多实例部署时各实例互不可见。
 * 用它在生产上做"分布式"锁 = 锁根本不存在。
 *
 * <p>原子性实现方式：{@link ConcurrentHashMap#compute} 对同一个 key 是串行的，
 * 所以"读-判断-写"放在 compute 的 lambda 里就是原子的 —— 对应 Redis 侧用 Lua 完成同一件事。
 *
 * <p><b>已知限制</b>：
 * <ul>
 *   <li>不主动清理过期 key。只在访问同一个 key 时顺带判定过期。长时间运行且 key 空间
 *       不断增长时会占用内存 —— 可调用 {@link #purgeExpired()} 手动清理。</li>
 *   <li>时间来自注入的 {@link Clock}，测试里可以换成可控时钟，不必 sleep。</li>
 * </ul>
 */
public class LocalGuardianStorage implements GuardianStorage {

    /** 一条占位记录。{@code expireAtMillis} 可变，因为 {@link #renew} 要原地延长。 */
    private static final class Entry {
        final String value;
        volatile long expireAtMillis;

        Entry(String value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }

        boolean isExpired(long nowMillis) {
            return nowMillis >= expireAtMillis;
        }
    }

    /** 滑动窗口：存窗口内每次请求的时间戳，按时间升序。 */
    private static final class Window {
        final Deque<Long> stamps = new ArrayDeque<>();
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocalGuardianStorage() {
        this(Clock.systemDefaultZone());
    }

    /**
     * @param clock 时间源。测试里传可控时钟即可精确验证过期行为，不用 {@code Thread.sleep}
     */
    public LocalGuardianStorage(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long now = clock.millis();
        Entry candidate = new Entry(value, now + ttl.toMillis());
        // compute 对同一 key 串行：返回 candidate 说明原来没有活着的记录，即占位成功。
        Entry result = entries.compute(key, (k, current) -> {
            if (current == null || current.isExpired(now)) {
                return candidate;
            }
            return current;
        });
        // 用引用相等判断：只有"我们的 candidate 被写进去"才算首次
        return result == candidate;
    }

    @Override
    public boolean releaseIfMatch(String key, String value) {
        long now = clock.millis();
        // compute 的 lambda 里不能改外部局部变量，用单元素数组带出结果
        boolean[] released = {false};
        entries.compute(key, (k, current) -> {
            if (current == null || current.isExpired(now)) {
                return null;
            }
            if (!current.value.equals(value)) {
                // 不是自己的锁，原样保留 —— 这就是"防误删他人锁"
                return current;
            }
            released[0] = true;
            return null;
        });
        return released[0];
    }

    @Override
    public Optional<String> getResult(String key) {
        long now = clock.millis();
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(now)) {
            // 顺手清掉，避免过期记录一直占着 map
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    @Override
    public boolean setResult(String key, String value, Duration ttl) {
        entries.put(key, new Entry(value, clock.millis() + ttl.toMillis()));
        return true;
    }

    @Override
    public long incrementInWindow(String key, Duration window, int permits, long nowMillis) {
        long cutoff = nowMillis - window.toMillis();
        // 计数必须在 compute 内取：出来再读 size 会被并发修改
        long[] ordinal = {0};
        windows.compute(key, (k, current) -> {
            Window w = (current == null) ? new Window() : current;
            // 移出窗口左边界（含边界：恰好落在 cutoff 上的算过期，窗口是左开右闭 (cutoff, now]）
            while (!w.stamps.isEmpty() && w.stamps.peekFirst() <= cutoff) {
                w.stamps.pollFirst();
            }
            long wouldBe = w.stamps.size() + 1L;
            ordinal[0] = wouldBe;
            // 超限就不记录：与 Redis 脚本保持一致，避免拒绝的请求挤占配额
            if (wouldBe <= permits) {
                w.stamps.addLast(nowMillis);
            }
            return w;
        });
        return ordinal[0];
    }

    @Override
    public boolean renew(String key, String value, Duration ttl) {
        long now = clock.millis();
        boolean[] renewed = {false};
        entries.compute(key, (k, current) -> {
            if (current == null || current.isExpired(now)) {
                return current;
            }
            if (!current.value.equals(value)) {
                // 锁已被别人拿走，不能替别人续期
                return current;
            }
            current.expireAtMillis = now + ttl.toMillis();
            renewed[0] = true;
            return current;
        });
        return renewed[0];
    }

    /**
     * 手动清理已过期的记录与空窗口。
     *
     * <p>本实现不启动任何后台线程（starter 不该偷偷给用户加线程），
     * 所以内存回收交给调用方在合适时机触发。
     */
    public void purgeExpired() {
        long now = clock.millis();
        entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
        windows.entrySet().removeIf(e -> e.getValue().stamps.isEmpty());
    }

    /** 当前占位记录数量，仅用于测试与诊断。 */
    public int size() {
        return entries.size();
    }
}
