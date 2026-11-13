package com.github.guardian.storage;

import java.time.Duration;
import java.util.Optional;

/**
 * 存储 SPI —— 三个切面对 Redis 的<b>全部</b>依赖都收在这个接口里。
 *
 * <p>分层的意图：切面只负责"拦截 + 取 key + 调用能力"，能力只依赖本接口。
 * 这样换存储（Redis / 本地 / 用户自定义）不影响切面逻辑。
 *
 * <p><b>实现者必须保证的原子性</b>：{@link #releaseIfMatch}、{@link #incrementInWindow}
 * 必须原子完成"判断 + 写"。Redis 实现走 Lua 脚本，本地实现走每个 key 的串行化临界区。
 * 原因："GET 再 DEL/INCR" 两条命令非原子，并发下会误删他人锁、会超发。
 *
 * <p><b>异常语义</b>：存储不可用时<b>抛</b>
 * {@code com.github.guardian.exception.GuardianUnavailableException}，
 * <b>不要 catch 后返回 false / 0</b>。返回 false 表示"确实已存在"，返回 0 表示"窗口内无记录"，
 * 与"状态未知"是两种语义。混在一起会让 Redis 故障时全部请求被误判为重复提交。
 * 由切面按各组件的 {@code fail-open} 决定放行还是拒绝。
 */
public interface GuardianStorage {

    /**
     * 原子占位，返回 {@code true} 表示首次。
     *
     * <p>语义等价于 {@code SET key value NX EX ttl}：key 不存在（或已过期）才写入。
     *
     * @return {@code true} = 占据成功（首次）；{@code false} = 已存在（别人在跑或已跑完）
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /**
     * 原子释放，<b>校验 value</b>，防止误删他人锁。
     *
     * <p>为什么必须校验：线程 A 拿到锁 &rarr; GC 停顿超过租期 &rarr; 锁自动过期 &rarr;
     * 线程 B 拿到锁 &rarr; A 恢复后在 finally 里删 key &rarr; <b>把 B 的锁删了</b>。
     * 这是最经典的分布式锁 bug
     *
     * @return {@code true} = 值匹配且已删除；{@code false} = key 不存在或值不匹配（不是自己的锁）
     */
    boolean releaseIfMatch(String key, String value);

    /**
     * 读取占位记录。
     *
     * <p>返回值有两种形态：{@code PENDING}（首次请求还在跑）或
     * {@code DONE:{"v":...}}（已完成，带结果）。key 不存在 &rarr; {@code Optional.empty()}。
     *
     * <p>注意"三态"是靠两态 + key 是否存在表达的，不额外建状态字段。
     */
    Optional<String> getResult(String key);

    /**
     * 覆写占位记录。业务执行完成后写 {@code DONE} + 结果，覆盖原来的 {@code PENDING}。
     *
     * @return {@code true} = 写入成功
     */
    boolean setResult(String key, String value, Duration ttl);

    /**
     * 滑动窗口计数，返回本次请求在窗口内的<b>序号</b>（1-based）。
     *
     * <p>调用方判定：{@code 返回值 > permits} 即超限。
     *
     * <p><b>为什么把 permits 传进来而不是让调用方自己比</b>："读计数 → 判断 → 写入"
     * 必须在存储层一次原子完成。否则先 {@code getCount()} 再 {@code incr()} 会有竞态，
     * 高并发下照样超发——这正是本项目要演示的那个坑。
     *
     * <p><b>被拒绝的请求不记录</b>：否则洪水攻击会把 ZSET 撑大，且合法用户被自己的
     * 重试挤出窗口。所以拒绝时返回的序号只是"本次本该排到第几位"，并不真的占用配额。
     *
     * <p><b>时间戳由调用方传入</b>，不是由存储层取当前时间。Redis 实现里必须这样 ——
     * {@code redis.call('time')} 是非确定性命令，Redis 5.0 之前脚本要求确定性，
     * 非确定性命令之后再写会直接报
     * {@code ERR Write commands not allowed after non deterministic commands}。
     *
     * @param window    窗口长度
     * @param permits   窗口内允许的请求数
     * @param nowMillis 当前时间戳（毫秒），由切面取 {@code System.currentTimeMillis()}
     * @return 本次请求在窗口内的序号；{@code > permits} 表示超限（未记录）
     */
    long incrementInWindow(String key, Duration window, int permits, long nowMillis);

    /**
     * 续期，用于看门狗。
     *
     * <p>同样<b>校验 value</b>：只有仍然持有该锁的持有者才能续期，
     * 否则会出现"A 的续期线程把 B 已拿到的锁续上了"。
     *
     * @return {@code true} = 续期成功；{@code false} = key 已不存在或值不匹配（锁已丢失）
     */
    boolean renew(String key, String value, Duration ttl);
}
