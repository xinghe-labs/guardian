package com.github.guardian.storage;

import com.github.guardian.exception.GuardianException;
import com.github.guardian.exception.GuardianUnavailableException;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 实现 —— 生产环境用的那个。
 *
 * <p>三条"必须原子"的操作全部走 Lua：
 * <ul>
 *   <li>{@link #releaseIfMatch} —— {@code GET + DEL} 两条命令非原子，中间被插队就会误删他人的锁</li>
 *   <li>{@link #renew} —— 同理，{@code GET + PEXPIRE}</li>
 *   <li>{@link #incrementInWindow} —— {@code ZCARD + ZADD}，先读后写在并发下必然超发</li>
 * </ul>
 * 只有 {@code SET NX PX} 本身就是原子的，不用脚本。
 *
 * <p><b>Lua 里不能用 {@code redis.call('time')}</b>：它是非确定性命令，Redis 5.0 之前
 * 脚本要求确定性（整个脚本复制给从库重放），非确定性命令之后再写会直接报
 * {@code ERR Write commands not allowed after non deterministic commands}。
 * 所以时间戳一律由调用方传进来。
 *
 * <p><b>实测环境是 Redis 3.0.504</b>（Windows 移植版），本类用到的
 * {@code SET NX PX} / {@code EVAL} / {@code ZADD} / {@code PEXPIRE} / {@code ZREMRANGEBYSCORE}
 * 在 3.0 全部可用，兼容 5.0+。
 *
 * <p><b>异常一律抛，不吞</b>：Redis 超时/断连时返回 {@code false} 会被上层当成
 * "已经有人占位了"，于是所有请求都被误判为重复提交 —— 一次 Redis 抖动变成全站拒绝。
 * 见 {@link GuardianStorage} 的异常语义说明。
 */
public class RedisGuardianStorage implements GuardianStorage {

    private final StringRedisTemplate redis;

    private final RedisScript<Long> releaseScript;
    private final RedisScript<Long> renewScript;
    private final RedisScript<Long> slidingWindowScript;

    public RedisGuardianStorage(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = script("lua/release-if-match.lua");
        this.renewScript = script("lua/renew.lua");
        this.slidingWindowScript = script("lua/sliding-window.lua");
    }

    private static RedisScript<Long> script(String location) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(location));
        s.setResultType(Long.class);
        return s;
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        // SET key value PX <ms> NX —— 单条命令，天然原子
        Boolean ok = call("setIfAbsent " + key,
                () -> redis.opsForValue().setIfAbsent(key, value, ttl));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean releaseIfMatch(String key, String value) {
        Long released = call("releaseIfMatch " + key,
                () -> redis.execute(releaseScript, Collections.singletonList(key), value));
        return released != null && released > 0L;
    }

    @Override
    public Optional<String> getResult(String key) {
        return Optional.ofNullable(call("getResult " + key, () -> redis.opsForValue().get(key)));
    }

    @Override
    public boolean setResult(String key, String value, Duration ttl) {
        call("setResult " + key, () -> {
            redis.opsForValue().set(key, value, ttl);
            return Boolean.TRUE;
        });
        return true;
    }

    @Override
    public long incrementInWindow(String key, Duration window, int permits, long nowMillis) {
        // member 必须每次不同：ZSET 会按 member 去重，用固定值的话计数永远不涨
        String member = nowMillis + "-" + UUID.randomUUID();
        Long ordinal = call("incrementInWindow " + key,
                () -> redis.execute(slidingWindowScript, Collections.singletonList(key),
                        Long.toString(nowMillis),
                        Long.toString(window.toMillis()),
                        Integer.toString(permits),
                        member));
        if (ordinal == null) {
            // 脚本正常必然有返回值；拿不到说明链路有问题，不能当成 0（= 放行）
            throw new GuardianUnavailableException(
                    "限流脚本返回空值，key=" + key);
        }
        return ordinal;
    }

    @Override
    public boolean renew(String key, String value, Duration ttl) {
        Long renewed = call("renew " + key,
                () -> redis.execute(renewScript, Collections.singletonList(key),
                        value, Long.toString(ttl.toMillis())));
        return renewed != null && renewed > 0L;
    }

    /** 统一把 Redis 的运行时异常翻译成 {@link GuardianUnavailableException}。 */
    private <T> T call(String what, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GuardianException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GuardianUnavailableException("Redis 操作失败（" + what + "）", e);
        }
    }
}
