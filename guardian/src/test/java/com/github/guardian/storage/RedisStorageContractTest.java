package com.github.guardian.storage;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 把 {@link GuardianStorageContractTest} 的契约套到 Redis 实现上。
 *
 * <p>这是整个契约测试的意义所在：上面那 12 条不变量，本地实现和 Redis 实现跑的是
 * <b>同一份断言</b>。只要有一边行为分叉（窗口左边界闭不闭合、ttl 是覆盖还是累加、
 * 并发是否原子），这里就会红——而分叉正是"测试环境通过、换存储上线出错"的根因。
 *
 * <p>Redis 不可用时整类跳过，不让没装 Redis 的机器红一片。
 */
class RedisStorageContractTest extends GuardianStorageContractTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6399;

    private static LettuceConnectionFactory factory;
    private static RedisGuardianStorage storage;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 Redis " + HOST + ":" + PORT + " 不可用，跳过 Redis 契约测试: "
                    + e.getMessage());
        }
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        storage = new RedisGuardianStorage(template);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Override
    protected GuardianStorage storage() {
        return storage;
    }

    @Override
    protected String uniqueKey(String suffix) {
        // 带随机前缀：契约测试里都是"同一 key 多次操作"，用例之间必须完全隔离
        return "contract:" + suffix + ":" + UUID.randomUUID();
    }
}
