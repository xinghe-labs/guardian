package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.guardian.annotation.RateLimit;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.RateLimitStrategy;
import com.github.guardian.exception.GuardianUnavailableException;
import com.github.guardian.exception.RateLimitExceededException;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.KeyResolver;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * 限流切面测试。
 *
 * <p>这里有一条硬规矩：<b>"并发场景的断言必须是确定性的"</b>。
 * 所以这里不写"并发 100 次大概放行 10 次"，而是走<b>顺序</b>请求 ——
 * permits=3 时第 4 次必然被拒，这个断言在任何机器、任何调度下都成立。
 * 真正的并发原子性在 {@code RedisGuardianStorageTest} 里由 Lua 保证，不在这里赌运气。
 */
class RateLimitAspectTest extends RedisAspectTestBase {

    private GuardianProperties properties;
    private LimitService proxy;

    // ---- 测试夹具 ----

    /** 被测业务类。注解写在具体方法上，切面靠 {@code @annotation} 绑定。 */
    static class LimitService {

        @RateLimit(key = "#userId", permits = 3, window = 1, unit = TimeUnit.SECONDS)
        String limited(String userId) {
            return "ok:" + userId;
        }

        @RateLimit(key = "#userId", permits = 3, window = 1, unit = TimeUnit.SECONDS,
                message = "慢点")
        String limitedWithMessage(String userId) {
            return "ok:" + userId;
        }

        @RateLimit(key = "#userId", strategy = RateLimitStrategy.WAIT)
        String waiting(String userId) {
            return "ok:" + userId;
        }

        @RateLimit(key = "'literal:' + #userId", permits = 1, window = 10)
        String literalKey(String userId) {
            return "ok:" + userId;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new GuardianProperties();
        properties.setKeyPrefix(prefix);
        proxy = proxyFor(storage, properties);
    }

    private LimitService proxyFor(GuardianStorage backing, GuardianProperties props) {
        RateLimitAspect aspect = new RateLimitAspect(backing, props, new KeyResolver(),
                new GuardianKeys(props.getKeyPrefix()));
        AspectJProxyFactory factory = new AspectJProxyFactory(new LimitService());
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    // ---- 用例 ----

    @Test
    @DisplayName("permits=3：前 3 次放行，第 4 次必被拒（确定性断言，不依赖并发调度）")
    void rejectsBeyondPermits() {
        assertThat(proxy.limited("u1")).isEqualTo("ok:u1");
        assertThat(proxy.limited("u1")).isEqualTo("ok:u1");
        assertThat(proxy.limited("u1")).isEqualTo("ok:u1");

        assertThatThrownBy(() -> proxy.limited("u1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("请求过于频繁");
    }

    @Test
    @DisplayName("不同 key 维度独立计数")
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            proxy.limited("u1");
        }
        // u1 已用满，u2 是全新的计数
        assertThat(proxy.limited("u2")).isEqualTo("ok:u2");
        assertThatThrownBy(() -> proxy.limited("u1")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("窗口滑过后重新放行（ZSET 的 score 落在窗口外的记录会被清掉）")
    void windowSlides() throws Exception {
        for (int i = 0; i < 3; i++) {
            proxy.limited("u1");
        }
        assertThatThrownBy(() -> proxy.limited("u1")).isInstanceOf(RateLimitExceededException.class);

        // 窗口 1s，等 1.2s 让前 3 条记录滑出
        Thread.sleep(1200L);

        assertThat(proxy.limited("u1")).isEqualTo("ok:u1");
    }

    @Test
    @DisplayName("抛出的异常带注解上的自定义 message")
    void usesCustomMessage() {
        for (int i = 0; i < 3; i++) {
            proxy.limitedWithMessage("u1");
        }
        assertThatThrownBy(() -> proxy.limitedWithMessage("u1"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("慢点");
    }

    @Test
    @DisplayName("策略 WAIT 未实现时抛 UnsupportedOperationException，而不是静默按 REJECT 处理")
    void waitStrategyIsExplicitlyUnsupported() {
        assertThatThrownBy(() -> proxy.waiting("u1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("WAIT 未实现");
    }

    @Test
    @DisplayName("key 表达式支持字面量拼接")
    void supportsLiteralConcatenation() {
        assertThat(proxy.literalKey("u9")).isEqualTo("ok:u9");
        assertThatThrownBy(() -> proxy.literalKey("u9"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("Redis 不可用 + failOpen=true（默认）：放行业务，不把限流故障变成全站 502")
    void failOpenProceedsWhenStorageUnavailable() {
        GuardianStorage broken = mock(GuardianStorage.class);
        when(broken.incrementInWindow(anyString(), org.mockito.ArgumentMatchers.any(Duration.class),
                anyInt(), anyLong()))
                .thenThrow(new GuardianUnavailableException("redis down"));

        LimitService failing = proxyFor(broken, properties);
        assertThat(failing.limited("u1")).isEqualTo("ok:u1");
    }

    @Test
    @DisplayName("Redis 不可用 + failOpen=false：把 GuardianUnavailableException 抛给调用方")
    void failClosedWhenConfigured() {
        properties.getRateLimit().setFailOpen(false);
        GuardianStorage broken = mock(GuardianStorage.class);
        when(broken.incrementInWindow(anyString(), org.mockito.ArgumentMatchers.any(Duration.class),
                anyInt(), anyLong()))
                .thenThrow(new GuardianUnavailableException("redis down"));

        LimitService failing = proxyFor(broken, properties);
        assertThatThrownBy(() -> failing.limited("u1"))
                .isInstanceOf(GuardianUnavailableException.class)
                .hasMessage("redis down");
    }
}
