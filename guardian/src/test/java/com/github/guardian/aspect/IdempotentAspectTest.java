package com.github.guardian.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.guardian.annotation.Idempotent;
import com.github.guardian.config.GuardianProperties;
import com.github.guardian.enums.ExceptionPolicy;
import com.github.guardian.exception.DuplicateRequestException;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.LocalGuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * 幂等切面测试。
 *
 * <p>"幂等在失败路径上比成功路径更容易出错"，所以用例的重心是：
 * <ul>
 *   <li>三态（首次 / 进行中 / 已完成）分别返回什么 —— 不能一句"直接拒绝"</li>
 *   <li>业务抛异常后 key 有没有被正确释放 —— 搞错就把失败请求永久锁死了</li>
 *   <li>{@code cacheResult = false} 时不能假装返回了缓存结果</li>
 * </ul>
 *
 * <p>用 {@link AspectJProxyFactory} 而不是直接调 {@code around()}：
 * 切面靠 {@code MethodSignature} 拿方法，只有真代理才走得到那条路径。
 */
class IdempotentAspectTest {

    private GuardianStorage storage;
    private GuardianProperties properties;
    private GuardianKeys keys;
    private OrderService target;

    // ---- 测试夹具 ----

    record OrderReq(String orderNo) {
    }

    record OrderResult(String status) {
    }

    static class BusinessException extends RuntimeException {
        BusinessException(String message) {
            super(message);
        }
    }

    static class OrderService {
        final AtomicInteger calls = new AtomicInteger();

        @Idempotent(key = "#req.orderNo", cacheResult = true)
        public OrderResult submit(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("OK-" + req.orderNo());
        }

        @Idempotent(key = "#req.orderNo", cacheResult = true, onException = ExceptionPolicy.RELEASE)
        public OrderResult failThenRelease(OrderReq req) {
            calls.incrementAndGet();
            throw new BusinessException("业务失败 " + req.orderNo());
        }

        @Idempotent(key = "#req.orderNo", cacheResult = true, onException = ExceptionPolicy.KEEP)
        public OrderResult failThenKeep(OrderReq req) {
            calls.incrementAndGet();
            throw new BusinessException("业务失败 " + req.orderNo());
        }

        @Idempotent(key = "#req.orderNo")
        public OrderResult submitWithoutCache(OrderReq req) {
            calls.incrementAndGet();
            return new OrderResult("OK-" + req.orderNo());
        }
    }

    @BeforeEach
    void setUp() {
        storage = new LocalGuardianStorage(Clock.systemUTC());
        properties = new GuardianProperties();
        keys = new GuardianKeys("test:");
        target = new OrderService();
    }

    private OrderService proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAspect(
                storage, properties, new KeyResolver(), keys, new JacksonResultCodec(null)));
        return factory.getProxy();
    }

    // ---- 用例 ----

    @Test
    @DisplayName("首次执行：业务跑一次，返回真实结果")
    void firstCallExecutesBusiness() {
        OrderResult result = proxy().submit(new OrderReq("A1"));
        assertThat(result).isEqualTo(new OrderResult("OK-A1"));
        assertThat(target.calls).hasValue(1);
    }

    @Test
    @DisplayName("已完成：第二次请求不再执行业务，直接返回缓存结果（cacheResult=true）")
    void secondCallReturnsCachedResult() {
        OrderService proxy = proxy();

        OrderResult first = proxy.submit(new OrderReq("A2"));
        OrderResult second = proxy.submit(new OrderReq("A2"));

        assertThat(target.calls).as("业务只该执行一次").hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void differentKeysAreIndependent() {
        OrderService proxy = proxy();
        proxy.submit(new OrderReq("A3"));
        proxy.submit(new OrderReq("A4"));
        assertThat(target.calls).hasValue(2);
    }

    @Test
    @DisplayName("进行中（PENDING）：抛 DuplicateRequestException，且带上注解里的 message")
    void pendingStateThrows() {
        String key = keys.idempotent("A5");
        storage.setIfAbsent(key, IdempotentAspect.PENDING, Duration.ofMinutes(1));

        assertThatThrownBy(() -> proxy().submit(new OrderReq("A5")))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("请勿重复提交");

        assertThat(target.calls).as("进行中的请求不该让业务再跑一次").hasValue(0);
    }

    @Test
    @DisplayName("cacheResult=false：第二次同样拒绝，不能假装返回了结果")
    void withoutCacheResultSecondCallIsRejected() {
        OrderService proxy = proxy();
        proxy.submitWithoutCache(new OrderReq("A6"));

        assertThatThrownBy(() -> proxy.submitWithoutCache(new OrderReq("A6")))
                .isInstanceOf(DuplicateRequestException.class);
        assertThat(target.calls).hasValue(1);
    }

    @Test
    @DisplayName("业务抛异常 + RELEASE（默认）：key 立即释放，同一 key 可重试")
    void releasePolicyAllowsRetry() {
        OrderService proxy = proxy();

        assertThatThrownBy(() -> proxy.failThenRelease(new OrderReq("A7")))
                .isInstanceOf(BusinessException.class);

        assertThat(storage.getResult(keys.idempotent("A7")))
                .as("RELEASE 语义下 key 必须已经被删掉")
                .isEmpty();

        // 重试能真正进到业务里（再抛一次说明确实执行了）
        assertThatThrownBy(() -> proxy.failThenRelease(new OrderReq("A7")))
                .isInstanceOf(BusinessException.class);
        assertThat(target.calls).as("两次都执行了业务").hasValue(2);
    }

    @Test
    @DisplayName("业务抛异常 + KEEP：key 保留，重试被拒绝（谨慎选择这个策略）")
    void keepPolicyBlocksRetry() {
        OrderService proxy = proxy();

        assertThatThrownBy(() -> proxy.failThenKeep(new OrderReq("A8")))
                .isInstanceOf(BusinessException.class);
        assertThat(storage.getResult(keys.idempotent("A8")))
                .as("KEEP 语义下 key 必须还在")
                .isPresent();

        assertThatThrownBy(() -> proxy.failThenKeep(new OrderReq("A8")))
                .isInstanceOf(DuplicateRequestException.class);
        assertThat(target.calls).as("第二次没进业务").hasValue(1);
    }
}
