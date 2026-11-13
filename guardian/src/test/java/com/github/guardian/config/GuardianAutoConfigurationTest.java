package com.github.guardian.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.guardian.aspect.IdempotentAspect;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.LocalGuardianStorage;
import com.github.guardian.storage.RedisGuardianStorage;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 自动装配与配置绑定测试。
 *
 * <p>这套用例对应配置绑定的四个陷阱：那两个"不报错但静默失效"的坑，正是本项目最容易白花半天的地方。
 */
class GuardianAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    GuardianRedisStorageAutoConfiguration.class,
                    GuardianLocalStorageAutoConfiguration.class,
                    GuardianAutoConfiguration.class));

    @Test
    @DisplayName("默认：不写任何配置也能启动，且默认值与文档一致")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GuardianProperties.class);

            GuardianProperties p = context.getBean(GuardianProperties.class);
            assertThat(p.isEnabled()).isTrue();
            assertThat(p.getKeyPrefix()).isEqualTo("guardian:");

            // 幂等：10 分钟，fail-closed（宁拒绝不重复执行）
            assertThat(p.getIdempotent().getDefaultTtl()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.getIdempotent().isFailOpen()).isFalse();

            // 限流：10 次 / 1 秒，fail-open（保可用性）
            assertThat(p.getRateLimit().getDefaultPermits()).isEqualTo(10);
            assertThat(p.getRateLimit().getDefaultWindow()).isEqualTo(Duration.ofSeconds(1));
            assertThat(p.getRateLimit().isFailOpen()).isTrue();

            // 锁：30 秒 + 看门狗，fail-closed（拿不到锁就放行等于锁不存在）
            assertThat(p.getLock().getDefaultLease()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.getLock().isWatchdogEnabled()).isTrue();
            assertThat(p.getLock().getWatchdogRenewInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.getLock().isFailOpen()).isFalse();
        });
    }

    @Test
    @DisplayName("三个 fail-open 默认值刻意不一致：幂等与锁 fail-closed，限流 fail-open")
    void failOpenDefaultsAreDeliberatelyDifferent() {
        runner.run(context -> {
            GuardianProperties p = context.getBean(GuardianProperties.class);
            assertThat(p.getIdempotent().isFailOpen()).as("重复执行的代价大于暂时不可用").isFalse();
            assertThat(p.getRateLimit().isFailOpen()).as("限流失效好过全站 502").isTrue();
            assertThat(p.getLock().isFailOpen()).as("拿不到锁就放行等于锁不存在").isFalse();
        });
    }

    @Test
    @DisplayName("guardian.enabled=false：整套配置不注册")
    void disabled() {
        runner.withPropertyValues("guardian.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(GuardianProperties.class);
                    assertThat(context).doesNotHaveBean(GuardianAutoConfiguration.class);
                });
    }

    @Test
    @DisplayName("有 StringRedisTemplate 时选 RedisGuardianStorage（分布式语义）")
    void redisStorageWinsWhenTemplatePresent() {
        runner.withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GuardianStorage.class)).isInstanceOf(RedisGuardianStorage.class);
                    // 存储就绪后切面才注册 —— 这个顺序靠嵌套 @Configuration 的声明顺序保证
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    @DisplayName("没有 Redis 时回退 LocalGuardianStorage，而不是什么都不注册")
    void localStorageIsTheFallback() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GuardianStorage.class)).isInstanceOf(LocalGuardianStorage.class);
            assertThat(context).hasSingleBean(IdempotentAspect.class);
        });
    }

    @Test
    @DisplayName("用户自己声明 GuardianStorage 时优先于两种内置实现")
    void userStorageWins() {
        GuardianStorage mine = Mockito.mock(GuardianStorage.class);
        runner.withBean(GuardianStorage.class, () -> mine)
                .withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .run(context -> assertThat(context.getBean(GuardianStorage.class)).isSameAs(mine));
    }

    @Test
    @DisplayName("陷阱 1：带单位的时间值正确绑定")
    void durationWithUnitBinds() {
        runner.withPropertyValues("guardian.idempotent.default-ttl=10m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GuardianProperties.class).getIdempotent().getDefaultTtl())
                            .isEqualTo(Duration.ofMinutes(10));
                });
    }

    @Test
    @DisplayName("陷阱 2：单位拼错 (10x) 启动失败")
    void badUnitFailsFast() {
        runner.withPropertyValues("guardian.idempotent.default-ttl=10x")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("陷阱 3：纯数字 10 会被当成 10ms —— 启动时拦住（否则幂等窗口 0.01s，等于没做幂等）")
    void bareNumberIsCaughtByValidation() {
        runner.withPropertyValues("guardian.idempotent.default-ttl=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .message()
                            .contains("guardian.idempotent.default-ttl")
                            .contains("必须 >= 1s");
                });
    }

    @Test
    @DisplayName("陷阱 3 补充：纯数字租期同样被拦（10ms 租期会让锁立刻过期）")
    void bareNumberLeaseIsCaught() {
        runner.withPropertyValues("guardian.lock.default-lease=30")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .message()
                            .contains("guardian.lock.default-lease");
                });
    }

    @Test
    @DisplayName("陷阱 4：字段名拼错 (default-tll) 启动失败，不再静默丢弃")
    void typoFieldFailsFast() {
        runner.withPropertyValues("guardian.idempotent.default-tll=10m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("续期间隔自动推导：lease=5s 时为 1.67s 而不是配置的 10s")
    void effectiveRenewIntervalFollowsSmallLease() {
        runner.withPropertyValues(
                        "guardian.lock.watchdog-renew-interval=10s",
                        "guardian.lock.default-lease=30s")
                .run(context -> {
                    GuardianProperties.Lock lock = context.getBean(GuardianProperties.class).getLock();

                    // 配置的 10s 是"上限"，注解把租期压到 5s 时自动缩到 5/3
                    Duration actual = lock.effectiveRenewInterval(Duration.ofSeconds(5));
                    assertThat(actual).isEqualTo(Duration.ofSeconds(5).dividedBy(3));
                    assertThat(actual.toMillis()).isEqualTo(1666);

                    // 租期够大时用配置值
                    assertThat(lock.effectiveRenewInterval(Duration.ofSeconds(30)))
                            .isEqualTo(Duration.ofSeconds(10));
                    assertThat(lock.effectiveRenewInterval(Duration.ofSeconds(100)))
                            .isEqualTo(Duration.ofSeconds(10));

                    // 边界：租期 3s -> 1s
                    assertThat(lock.effectiveRenewInterval(Duration.ofSeconds(3)))
                            .isEqualTo(Duration.ofSeconds(1));
                });
    }

    @Test
    @DisplayName("续期间隔：配置值本身很小时取更小的那个")
    void effectiveRenewIntervalUsesSmallerConfig() {
        runner.withPropertyValues("guardian.lock.watchdog-renew-interval=1s")
                .run(context -> {
                    GuardianProperties.Lock lock = context.getBean(GuardianProperties.class).getLock();
                    assertThat(lock.effectiveRenewInterval(Duration.ofSeconds(30)))
                            .isEqualTo(Duration.ofSeconds(1));
                });
    }

    @Test
    @DisplayName("default-permits 配成 0 启动失败")
    void zeroPermitsFails() {
        runner.withPropertyValues("guardian.rate-limit.default-permits=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().message()
                            .contains("default-permits");
                });
    }
}
