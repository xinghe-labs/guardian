package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.annotation.Idempotent;
import com.github.guardian.annotation.RateLimit;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.RedisGuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import com.github.guardian.testutil.BlackholeServer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * S5 故障注入：不是 mock 抛异常，而是让客户端<b>真的</b>遇到网络故障。
 *
 * <p><b>FlakyStorage（见 {@code IdempotentAspectFailureTest}）证明的是
 * "切面对 {@code GuardianUnavailableException} 的 fail-open/closed 决策正确"；
 * 这里补上它证明不了的一半 —— "真实 Redis 故障真的会被翻译成
 * {@code GuardianUnavailableException}"。翻译逻辑在
 * {@link RedisGuardianStorage} 的 {@code call()} 里：catch 任何 RuntimeException。
 * 但"Lettuce 在三种故障下分别抛什么"是客户端行为，只有真 socket 能回答。
 * 如果 Lettuce 实际抛的东西不在 catch 范围内，故障会以 500 的形式穿透到用户。
 *
 * <p>三条故障路径：
 * <ol>
 *   <li>命令超时 —— 服务端接受连接但永不回包（{@link BlackholeServer.Mode#SILENT}）</li>
 *   <li>连接被断 —— 服务端接受后立刻关闭（{@link BlackholeServer.Mode#CLOSE_AFTER_ACCEPT}）</li>
 *   <li>连接拒绝 —— 端口上没有任何服务</li>
 * </ol>
 *
 * <p>命令超时设 500ms（默认 60s 会让测试卡死），并断言"在几秒内失败而不是挂死"。
 * 三条路径都要落在 {@link GuardianUnavailableException}，且 cause 保留原始异常 ——
 * 排查时"Redis 操作失败（...）"这句话必须能追到 Lettuce 层的根因。
 *
 * <p>最后用真实死掉的 Redis 验证三个组件<b>各自的默认降级方向</b>（对照三个组件各自的 fail-open 默认值表格）：
 * 幂等 fail-closed（拒绝）、限流 fail-open（放行）、锁 fail-closed（拒绝）。
 */
class RedisFaultInjectionTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);

    private BlackholeServer server;
    private LettuceConnectionFactory factory;

    private GuardianStorage deadStorage;
    private GuardianProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GuardianProperties();
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
        if (server != null) {
            server.close();
        }
    }

    /** 指向故障服务端起一套真实 Lettuce 客户端（命令超时 500ms）。 */
    private void connectTo(BlackholeServer.Mode mode) throws Exception {
        server = new BlackholeServer(mode);
        factory = factory(server.port());
        deadStorage = new RedisGuardianStorage(new StringRedisTemplate(factory));
    }

    /** 指向一个确定无人监听的端口（临时占用后立刻释放）。 */
    private void connectToRefusedPort() throws Exception {
        try (BlackholeServer tmp = new BlackholeServer(BlackholeServer.Mode.SILENT)) {
            int port = tmp.port();
            factory = factory(port);
            deadStorage = new RedisGuardianStorage(new StringRedisTemplate(factory));
        }
    }

    private LettuceConnectionFactory factory(int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .build();
        LettuceConnectionFactory f = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port), client);
        f.afterPropertiesSet();
        return f;
    }

    // ---- 存储层：三条路径都必须翻译成 GuardianUnavailableException ----

    @Test
    @DisplayName("命令超时（服务端不回包）：抛 GuardianUnavailableException，不挂死")
    void commandTimeoutBecomesUnavailable() throws Exception {
        connectTo(BlackholeServer.Mode.SILENT);

        long begin = System.nanoTime();
        assertThatThrownBy(() -> deadStorage.setIfAbsent("k1", "v", Duration.ofSeconds(10)))
                .isInstanceOf(GuardianUnavailableException.class)
                .hasMessageContaining("Redis 操作失败")
                .cause()
                .isNotNull();
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        // 500ms 命令超时 + 客户端建立连接的开销；10s 还没回来就说明哪里在无限等
        assertThat(elapsedMillis).as("必须在几秒内失败，而不是挂死").isLessThan(10_000L);
    }

    @Test
    @DisplayName("连接被服务端立刻关闭：抛 GuardianUnavailableException")
    void closedConnectionBecomesUnavailable() throws Exception {
        connectTo(BlackholeServer.Mode.CLOSE_AFTER_ACCEPT);

        assertThatThrownBy(() -> deadStorage.getResult("k2"))
                .isInstanceOf(GuardianUnavailableException.class)
                .hasMessageContaining("Redis 操作失败")
                .cause()
                .isNotNull();
    }

    @Test
    @DisplayName("连接拒绝（端口无人监听）：抛 GuardianUnavailableException")
    void refusedConnectionBecomesUnavailable() throws Exception {
        connectToRefusedPort();

        assertThatThrownBy(() -> deadStorage.incrementInWindow("k3", Duration.ofSeconds(10), 5,
                System.currentTimeMillis()))
                .isInstanceOf(GuardianUnavailableException.class)
                .hasMessageContaining("Redis 操作失败")
                .cause()
                .isNotNull();
    }

    // ---- 切面层：真实死 Redis 时，三个组件各自的默认降级方向 ----

    /** 目标业务：进入即计数并返回，用来断言"业务到底跑没跑"。 */
    static class OrderService {
        final AtomicInteger calls = new AtomicInteger();

        @Idempotent(key = "'order'")
        String submit() {
            calls.incrementAndGet();
            return "ran";
        }
    }

    static class LimitService {
        final AtomicInteger calls = new AtomicInteger();

        @RateLimit(key = "'global'", permits = 1, window = 30, unit = TimeUnit.SECONDS)
        String call() {
            calls.incrementAndGet();
            return "ran";
        }
    }

    static class LockService {
        final AtomicInteger calls = new AtomicInteger();

        @DistributedLock(key = "'resource'", waitTime = 0)
        String run() {
            calls.incrementAndGet();
            return "ran";
        }
    }

    private <T> T proxy(T target, Object aspect) {
        AspectJProxyFactory f = new AspectJProxyFactory(target);
        f.setProxyTargetClass(true);
        f.addAspect(aspect);
        return f.getProxy();
    }

    @Test
    @DisplayName("幂等默认 fail-closed：Redis 挂了必须拒绝，而不是放行业务（重复扣款比不可用贵）")
    void idempotentFailsClosedOnDeadRedis() throws Exception {
        connectTo(BlackholeServer.Mode.SILENT);
        OrderService target = new OrderService();
        OrderService p = proxy(target, new IdempotentAspect(deadStorage, properties,
                new KeyResolver(), new GuardianKeys("f:"), new JacksonResultCodec(null)));

        assertThatThrownBy(p::submit)
                .as("幂等默认 fail-closed，必须抛 GuardianUnavailableException")
                .isInstanceOf(GuardianUnavailableException.class);
        assertThat(target.calls).as("业务绝不能执行").hasValue(0);
    }

    @Test
    @DisplayName("限流默认 fail-open：Redis 挂了放行业务（限流失效好过全站拒绝）")
    void rateLimitFailsOpenOnDeadRedis() throws Exception {
        connectTo(BlackholeServer.Mode.SILENT);
        LimitService target = new LimitService();
        LimitService p = proxy(target, new RateLimitAspect(deadStorage, properties,
                new KeyResolver(), new GuardianKeys("f:")));

        assertThat(p.call()).as("fail-open 默认放行").isEqualTo("ran");
        assertThat(target.calls).hasValue(1);
    }

    @Test
    @DisplayName("锁默认 fail-closed：Redis 挂了拒绝执行（无锁并发写等于数据损坏）")
    void lockFailsClosedOnDeadRedis() throws Exception {
        connectTo(BlackholeServer.Mode.SILENT);
        Watchdog watchdog = new Watchdog(deadStorage);
        try {
            LockService target = new LockService();
            LockService p = proxy(target, new DistributedLockAspect(deadStorage, properties,
                    new KeyResolver(), new GuardianKeys("f:"), watchdog));

            assertThatThrownBy(p::run)
                    .as("锁默认 fail-closed，必须抛 GuardianUnavailableException")
                    .isInstanceOf(GuardianUnavailableException.class);
            assertThat(target.calls).as("业务绝不能在无锁状态下执行").hasValue(0);
        } finally {
            watchdog.destroy();
        }
    }
}
