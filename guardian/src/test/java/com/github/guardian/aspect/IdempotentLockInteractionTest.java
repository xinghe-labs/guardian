package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.annotation.Idempotent;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.ExceptionPolicy;
import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.exception.LockAcquireFailedException;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * {@code @Idempotent} + {@code @DistributedLock} 叠加时的顺序语义：先限流判重再抢锁，
 *
 * <p>下面记录一个<b>不写清楚就会踩</b>的副作用：
 * <pre>
 *   RateLimit(10) → Idempotent(20) → Lock(30)
 * </pre>
 * 幂等先占了位（写 {@code PENDING}），锁再等 3 秒没拿到就抛异常——业务方法<b>从未执行</b>，
 * 而幂等 key 会原样留到 ttl（默认 10 分钟）。用户立刻重试会被判"请勿重复提交"并持续 10 分钟。
 * <b>这不是幂等，是把失败请求永久锁死。</b>
 *
 * <p>所以异常语义里有一条例外：<b>锁获取失败 → 无条件释放，不受 {@code onException} 控制</b>。
 * 本类就是那条规则的回归测试。
 */
class IdempotentLockInteractionTest extends RedisAspectTestBase {

    private GuardianProperties properties;
    private Watchdog watchdog;
    private CombinedService proxy;

    // ---- 测试夹具 ----

    static class CombinedService {

        /** 业务真正进到方法里的信号（= 锁已到手）。 */
        static volatile CountDownLatch holderEntered;
        /** 测试放行信号：不让持锁方靠 sleep 撑时间，改由测试决定何时释放。 */
        static volatile CountDownLatch holderMayRelease;

        /**
         * 只拿锁，用来当"占位者"。key 表达式与下面一致，所以打的是同一个 Redis key。
         *
         * <p><b>不能用 {@code Thread.sleep(400)} 来撑持锁窗口</b>：本用例最初就是这么写的，
         * 结果 3 轮跑红 1 轮——主线程的 {@code sleep(120)} 在有负载时可能被拉长到超过 400ms，
         * 持锁方已经释放，第一次 {@code submit} 反而成功，{@code assertThatThrownBy} 直接挂。
         * 改成门闩：进方法后一直等测试放行，持锁窗口的长短完全由断言控制。
         */
        @DistributedLock(key = "#orderNo", waitTime = 0, leaseTime = 5, unit = TimeUnit.SECONDS)
        String holdLock(String orderNo) throws InterruptedException {
            CountDownLatch entered = holderEntered;
            if (entered != null) {
                entered.countDown();
            }
            CountDownLatch release = holderMayRelease;
            if (release != null) {
                // 上限 10s 只是防死锁，正常路径由测试放行
                release.await(10, TimeUnit.SECONDS);
            }
            return "held";
        }

        /** 幂等 + 锁叠加。waitTime=0，锁被占时立刻失败。 */
        @Idempotent(key = "#orderNo", ttl = 60, unit = TimeUnit.SECONDS)
        @DistributedLock(key = "#orderNo", waitTime = 0, leaseTime = 5, unit = TimeUnit.SECONDS)
        String submit(String orderNo) {
            return "submitted:" + orderNo;
        }

        /**
         * 对照组：业务自己抛异常 + {@code onException = KEEP}。
         * 这种情况占位<b>应该</b>保留（与锁失败相反），用来证明上面那条测试真的能区分两种分支。
         */
        @Idempotent(key = "#orderNo", ttl = 60, unit = TimeUnit.SECONDS,
                onException = ExceptionPolicy.KEEP)
        @DistributedLock(key = "#orderNo", waitTime = 0, leaseTime = 5, unit = TimeUnit.SECONDS)
        String submitKeepOnFailure(String orderNo) {
            throw new IllegalStateException("业务自己炸了");
        }
    }

    @BeforeEach
    void setUp() {
        properties = new GuardianProperties();
        properties.setKeyPrefix(prefix);
        watchdog = new Watchdog(storage);

        IdempotentAspect idempotentAspect = new IdempotentAspect(storage, properties,
                new KeyResolver(), new GuardianKeys(properties.getKeyPrefix()),
                new JacksonResultCodec(new ObjectMapper()));
        DistributedLockAspect lockAspect = new DistributedLockAspect(storage, properties,
                new KeyResolver(), new GuardianKeys(properties.getKeyPrefix()), watchdog);

        AspectJProxyFactory factory = new AspectJProxyFactory(new CombinedService());
        // 注册顺序故意反过来（先锁后幂等），验证真正生效的是 @Order 而不是注册顺序
        factory.addAspect(lockAspect);
        factory.addAspect(idempotentAspect);
        proxy = factory.getProxy();
    }

    @AfterEach
    void stopWatchdog() {
        watchdog.destroy();
    }

    // ---- 用例 ----

    @Test
    @DisplayName("锁获取失败必须释放幂等占位：立刻重试不被判重复提交")
    void releasesIdempotentPlaceholderWhenLockFails() throws Exception {
        String idemKey = prefix + "idem:o1";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch mayRelease = new CountDownLatch(1);
        CombinedService.holderEntered = entered;
        CombinedService.holderMayRelease = mayRelease;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> proxy.holdLock("o1"));
            // 等持锁方真的进去了，此时锁一定还在它手上（由门闩卡住，不会自行过期）
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 第一次：幂等占位成功 → 锁拿不到 → 抛 LockAcquireFailedException
            assertThatThrownBy(() -> proxy.submit("o1"))
                    .isInstanceOf(LockAcquireFailedException.class);

            // 关键断言：业务从未执行，占位必须已被释放
            assertThat(template.hasKey(idemKey))
                    .as("锁获取失败后幂等占位残留 —— 用户会被自己的失败请求挡住 60 秒")
                    .isFalse();

            mayRelease.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("held");

            // 立刻重试：必须真正执行，而不是 DuplicateRequestException
            assertThat(proxy.submit("o1")).isEqualTo("submitted:o1");
        } finally {
            mayRelease.countDown();
            CombinedService.holderEntered = null;
            CombinedService.holderMayRelease = null;
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("对照组：业务自己抛异常 + onException=KEEP，占位应保留（证明两种分支真的不同）")
    void keepsPlaceholderWhenBusinessFailsAndPolicyIsKeep() {
        String idemKey = prefix + "idem:o2";

        assertThatThrownBy(() -> proxy.submitKeepOnFailure("o2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("业务自己炸了");

        // KEEP：失败也留着占位，防止"失败后被无限重试打穿"
        assertThat(template.hasKey(idemKey)).isTrue();

        // 再调一次：占位还在且是 PENDING → 按 FAIL 策略抛"请勿重复提交"
        assertThatThrownBy(() -> proxy.submitKeepOnFailure("o2"))
                .isInstanceOf(DuplicateRequestException.class);
    }
}
