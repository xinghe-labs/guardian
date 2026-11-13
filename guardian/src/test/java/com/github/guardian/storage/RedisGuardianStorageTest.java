package com.github.guardian.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 对着<b>真实 Redis</b> 跑，不是 mock。
 *
 * <p>理由：这里要验的全是 Redis 语义本身 —— TTL 有没有真的设上、Lua 的 CAS 是否原子、
 * ZSET 滑动窗口的边界。mock 只能验证"我调用了哪个方法"，验不了这些。
 *
 * <p>Redis 不可用时整类跳过（{@code Assumptions}），不让没装 Redis 的机器红一片。
 * 本地验证环境：Redis <b>3.0.504</b> @ {@code 127.0.0.1:6399}。
 * 特意用这个老版本跑，是为了证明脚本没有依赖 Redis 5.0 才有的能力
 * （比如 {@code redis.call('time')}。
 */
class RedisGuardianStorageTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6399;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;
    private static RedisGuardianStorage storage;

    private final String prefix = "guardian-test:" + UUID.randomUUID() + ":";

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(HOST, PORT);
        factory.afterPropertiesSet();
        try (RedisConnection connection = factory.getConnection()) {
            connection.ping();
        } catch (RuntimeException e) {
            Assumptions.abort("本地 Redis " + HOST + ":" + PORT + " 不可用，跳过 Redis 存储测试: "
                    + e.getMessage());
        }
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        storage = new RedisGuardianStorage(template);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @AfterEach
    void cleanUp() {
        if (template != null) {
            template.delete(template.keys(prefix + "*"));
        }
    }

    @Test
    @DisplayName("setIfAbsent：首次 true、再次 false")
    void setIfAbsentOnlyOnce() {
        String key = prefix + "idem";
        assertThat(storage.setIfAbsent(key, "PENDING", Duration.ofSeconds(30))).isTrue();
        assertThat(storage.setIfAbsent(key, "PENDING", Duration.ofSeconds(30))).isFalse();
    }

    @Test
    @DisplayName("setIfAbsent 必须真的设上 TTL —— 否则锁永不过期，进程崩溃后该 key 永久锁死")
    void setIfAbsentSetsTtl() {
        String key = prefix + "ttl";
        storage.setIfAbsent(key, "PENDING", Duration.ofSeconds(30));

        Long ttlMillis = template.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis)
                .as("PTTL 必须是正数；-1 表示永不过期，那才是真正的 bug")
                .isNotNull()
                .isGreaterThan(0)
                .isLessThanOrEqualTo(30_000L);
    }

    @Test
    @DisplayName("releaseIfMatch：只删自己的值，不删别人的（防 GC 停顿后误删新持有者的锁）")
    void releaseOnlyOwnValue() {
        String key = prefix + "fencing";
        storage.setIfAbsent(key, "owner-A", Duration.ofSeconds(30));

        // 模拟：A 的租期到了，B 拿到锁
        assertThat(storage.releaseIfMatch(key, "owner-B"))
                .as("值不匹配必须返回 false，且不能删掉 key")
                .isFalse();
        assertThat(template.hasKey(key)).isTrue();

        // A 恢复后才发现自己早就过期了 —— 此时删除才是安全的
        assertThat(storage.releaseIfMatch(key, "owner-A")).isTrue();
        assertThat(template.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("结果回填：getResult 读到刚写进去的内容，key 不存在时返回 empty")
    void resultRoundTrip() {
        String key = prefix + "result";
        assertThat(storage.getResult(key)).isEmpty();

        storage.setResult(key, "DONE:{\"v\":1}", Duration.ofSeconds(30));
        Optional<String> stored = storage.getResult(key);
        assertThat(stored).contains("DONE:{\"v\":1}");
    }

    @Test
    @DisplayName("滑动窗口严格等于 permits：12 次请求前 10 放行、后 2 拒绝")
    void slidingWindowIsStrict() {
        String key = prefix + "rate";
        Duration window = Duration.ofSeconds(10);
        int permits = 10;
        long now = System.currentTimeMillis();

        for (int i = 1; i <= permits; i++) {
            assertThat(storage.incrementInWindow(key, window, permits, now))
                    .as("第 %d 次应当放行", i)
                    .isEqualTo(i);
        }
        for (int i = permits + 1; i <= permits + 2; i++) {
            assertThat(storage.incrementInWindow(key, window, permits, now))
                    .as("第 %d 次应当被拒（序号 > permits）", i)
                    .isGreaterThan(permits);
        }
    }

    @Test
    @DisplayName("被拒绝的请求不占用配额 —— 否则洪水攻击会把合法用户挤出窗口")
    void rejectedRequestsDoNotConsumeQuota() {
        String key = prefix + "rate-reject";
        Duration window = Duration.ofSeconds(10);
        int permits = 2;
        long now = System.currentTimeMillis();

        assertThat(storage.incrementInWindow(key, window, permits, now)).isEqualTo(1);
        assertThat(storage.incrementInWindow(key, window, permits, now)).isEqualTo(2);
        // 连续被拒 5 次
        for (int i = 0; i < 5; i++) {
            assertThat(storage.incrementInWindow(key, window, permits, now)).isGreaterThan(permits);
        }
        // ZSET 里仍然只有 2 条记录（用 ZCARD 直接查，不靠返回值推断）
        Long size = template.opsForZSet().zCard(key);
        assertThat(size).as("被拒绝的请求不该写进 ZSET").isEqualTo(2L);
    }

    @Test
    @DisplayName("窗口滑过之后重新放行")
    void windowSlides() {
        String key = prefix + "rate-slide";
        Duration window = Duration.ofSeconds(2);
        int permits = 1;
        long now = System.currentTimeMillis();

        assertThat(storage.incrementInWindow(key, window, permits, now)).isEqualTo(1);
        assertThat(storage.incrementInWindow(key, window, permits, now)).isGreaterThan(permits);

        // 把时间推进到窗口之外
        long later = now + window.toMillis() + 1;
        assertThat(storage.incrementInWindow(key, window, permits, later))
                .as("旧记录滑出窗口后应当重新放行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("renew：只有持有者能续期，且续期后 TTL 变长")
    void renewOnlyByOwner() {
        String key = prefix + "lease";
        storage.setIfAbsent(key, "owner-A", Duration.ofSeconds(5));

        assertThat(storage.renew(key, "owner-B", Duration.ofSeconds(60)))
                .as("非持有者续期必须失败")
                .isFalse();
        assertThat(template.getExpire(key, TimeUnit.MILLISECONDS))
                .as("失败的续期不能改变 TTL")
                .isLessThanOrEqualTo(5_000L);

        assertThat(storage.renew(key, "owner-A", Duration.ofSeconds(60))).isTrue();
        assertThat(template.getExpire(key, TimeUnit.MILLISECONDS))
                .as("续期后 TTL 应当接近 60s")
                .isGreaterThan(55_000L);
    }
}
