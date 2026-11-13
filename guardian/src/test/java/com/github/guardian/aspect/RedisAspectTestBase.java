package com.github.guardian.aspect;

import com.github.guardian.storage.RedisGuardianStorage;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S3 两个切面测试的公共底座：连<b>真实 Redis</b>。
 *
 * <p>为什么不用 {@code LocalGuardianStorage}：限流和锁的价值全在"多实例共享状态"，
 * 用本地实现测出来的通过率不代表任何东西。这里要验的恰好是 Redis 语义
 * （{@code SET NX PX} 的原子性、Lua 滑动窗口的边界、{@code PEXPIRE} 的续期）。
 *
 * <p>Redis 不可用时整类跳过（{@code Assumptions}），不让没装 Redis 的机器红一片。
 * 本地验证环境：Redis <b>3.0.504</b> @ {@code 127.0.0.1:6399}。
 */
abstract class RedisAspectTestBase {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6399;

    static LettuceConnectionFactory factory;
    static StringRedisTemplate template;
    static RedisGuardianStorage storage;

    /** 每个测试实例独立的 key 前缀，避免用例之间互相干扰。 */
    final String prefix = "guardian-s3:" + UUID.randomUUID() + ":";

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 Redis " + HOST + ":" + PORT + " 不可用，跳过 Redis 切面测试: "
                    + e.getMessage());
        }
        template = new StringRedisTemplate(factory);
        storage = new RedisGuardianStorage(template);
    }

    @AfterAll
    static void close() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = template.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys);
        }
    }
}
