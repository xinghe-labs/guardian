package com.github.guardian.support;

/**
 * 统一拼 Redis key：{@code <prefix><kind>:<业务 key>}。
 *
 * <p>集中在一处的好处：换 key 命名策略时只改一个类，且能一句话说清
 * "怎么避免和业务缓存撞 key"——前缀可配（{@code guardian.key-prefix}），三类能力各占一个命名空间：
 *
 * <pre>
 * guardian:idem:order:1001    幂等占位 / 结果
 * guardian:rl:user:42         限流窗口（ZSET）
 * guardian:lock:job:9001      分布式锁
 * </pre>
 *
 * <p><b>刻意不做哈希</b>：虽然 {@code md5(方法签名 + 参数)} 更短，但
 * 在 {@code redis-cli} 里 {@code KEYS guardian:idem:order:*} 能直接看出是哪个订单，
 * 排查"这个 key 怎么还在"时省掉一次反查。key 长度在这里不是瓶颈。
 */
public class GuardianKeys {

    private final String prefix;

    public GuardianKeys(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    public String idempotent(String key) {
        return prefix + "idem:" + key;
    }

    public String rateLimit(String key) {
        return prefix + "rl:" + key;
    }

    public String lock(String key) {
        return prefix + "lock:" + key;
    }
}
