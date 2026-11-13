package com.github.guardian.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guardian.aspect.DistributedLockAspect;
import com.github.guardian.aspect.IdempotentAspect;
import com.github.guardian.aspect.RateLimitAspect;
import com.github.guardian.lock.Watchdog;
import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.support.GuardianKeys;
import com.github.guardian.support.JacksonResultCodec;
import com.github.guardian.support.KeyResolver;
import com.github.guardian.support.ResultSerializer;
import com.github.guardian.support.UnavailableResultCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * guardian 的自动装配入口：配置绑定、SpEL 解析、key 拼装、结果序列化、切面。
 *
 * <p>存储 bean 不在这里，在 {@link GuardianRedisStorageAutoConfiguration} 与
 * {@link GuardianLocalStorageAutoConfiguration} 两个独立的顶层 auto-configuration 里 ——
 * 原因见前者的类注释（嵌套 {@code @Configuration} 的声明顺序不可靠）。
 *
 * <p>注册方式是 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * （Boot 2.7+ 的写法，一行一个 FQCN），<b>不是</b>老的 {@code spring.factories}。
 *
 * <p><b>不污染用户配置的三条纪律</b>：
 * <ol>
 *   <li>{@code @ConditionalOnMissingBean}：用户自己定义了同类型 bean 就优先生效</li>
 *   <li>所有配置项都有默认值，用户一行 yaml 不写也能跑</li>
 *   <li>只注册 {@code @Bean}，不做 {@code @ComponentScan} —— 扫描用户包是 starter 的大忌</li>
 * </ol>
 */
@AutoConfiguration(after = {
        RedisAutoConfiguration.class,
        GuardianRedisStorageAutoConfiguration.class,
        GuardianLocalStorageAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "guardian", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GuardianProperties.class)
public class GuardianAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardianAutoConfiguration.class);

    public GuardianAutoConfiguration(GuardianProperties properties) {
        log.info("guardian enabled, keyPrefix='{}', idempotent.ttl={}, rateLimit={}/{}, lock.lease={} (watchdog={})",
                properties.getKeyPrefix(),
                properties.getIdempotent().getDefaultTtl(),
                properties.getRateLimit().getDefaultPermits(),
                properties.getRateLimit().getDefaultWindow(),
                properties.getLock().getDefaultLease(),
                properties.getLock().isWatchdogEnabled() ? "on" : "off");
    }

    @Bean
    @ConditionalOnMissingBean
    public KeyResolver guardianKeyResolver() {
        return new KeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public GuardianKeys guardianKeys(GuardianProperties properties) {
        return new GuardianKeys(properties.getKeyPrefix());
    }

    // ------------------------------------------------------------------
    // 结果序列化：Jackson 优先，没有则用会报错的占位实现
    // ------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
    static class JacksonSerializerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ResultSerializer guardianResultSerializer(ObjectProvider<ObjectMapper> objectMapper) {
            // 用用户容器里的 ObjectMapper（继承了全局序列化配置，如日期格式、驼峰策略），
            // 没有就自己造一个
            return new JacksonResultCodec(objectMapper.getIfAvailable());
        }
    }

    /**
     * 没有 Jackson 时的兜底：不报错、不启动失败，只在真正用到
     * {@code cacheResult = true} 时才抛，并给出三种解法。
     *
     * <p><b>{@code @ConditionalOnMissingClass} 不是装饰，是修 bug</b>：最初只靠
     * {@code @ConditionalOnMissingBean} 让位，实测在 Jackson <b>存在</b>的 web 应用上
     * 本类反而赢了 —— 同一个自动配置类的两个嵌套 {@code @Configuration} 处理顺序
     * 不保证（{@code getDeclaredClasses()} 无序，与存储 bean 装配顺序是同一族问题），
     * Unavailable 先注册，Jackson 配置随后被 {@code @ConditionalOnMissingBean} 让位，
     * demo 启动日志出现「未找到 Jackson」的 WARN。修法是把两个条件改成<b>互斥</b>
     * （class 在 → 只 Jackson 生效；class 不在 → 只本类生效），彻底不依赖顺序。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("com.fasterxml.jackson.databind.ObjectMapper")
    static class UnavailableSerializerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ResultSerializer guardianUnavailableResultSerializer() {
            log.warn("guardian 未找到 Jackson，cacheResult=true 将不可用"
                    + "（需引入 jackson-databind 或自定义 ResultSerializer）");
            return new UnavailableResultCodec();
        }
    }

    // ------------------------------------------------------------------
    // 切面
    // ------------------------------------------------------------------

    /**
     * {@code @ConditionalOnBean(GuardianStorage.class)} 之所以能生效：本类通过
     * {@code @AutoConfiguration(after = ...)} 排在两个存储配置之后，Boot 保证那个顺序。
     * 若把存储放在本类的嵌套类里，这个条件就会因为处理顺序不确定而时灵时不灵。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
    @ConditionalOnBean(GuardianStorage.class)
    static class AspectConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotentAspect idempotentAspect(GuardianStorage storage,
                                          GuardianProperties properties,
                                          KeyResolver keyResolver,
                                          GuardianKeys keys,
                                          ResultSerializer codec) {
            return new IdempotentAspect(storage, properties, keyResolver, keys, codec);
        }

        /**
         * 三个切面都靠 {@code @Order} 排序：RateLimit(10) → Idempotent(20) → Lock(30)。
         * 这里<b>不需要</b>再手动排 bean 的创建顺序——{@code @Order} 是 Spring AOP 在
         * 排序时读的元数据，与 bean 实例化的先后无关。
         */
        @Bean
        @ConditionalOnMissingBean
        RateLimitAspect rateLimitAspect(GuardianStorage storage,
                                        GuardianProperties properties,
                                        KeyResolver keyResolver,
                                        GuardianKeys keys) {
            return new RateLimitAspect(storage, properties, keyResolver, keys);
        }

        /**
         * 看门狗是所有锁共用的单线程调度器，必须单例。
         * 它实现了 {@link org.springframework.beans.factory.DisposableBean}，
         * 容器关闭时会被调 {@code destroy()} 停掉线程——否则
         * {@code ScheduledExecutorService} 会泄漏。
         */
        @Bean
        @ConditionalOnMissingBean
        Watchdog guardianWatchdog(GuardianStorage storage) {
            return new Watchdog(storage);
        }

        @Bean
        @ConditionalOnMissingBean
        DistributedLockAspect distributedLockAspect(GuardianStorage storage,
                                                    GuardianProperties properties,
                                                    KeyResolver keyResolver,
                                                    GuardianKeys keys,
                                                    Watchdog watchdog) {
            return new DistributedLockAspect(storage, properties, keyResolver, keys, watchdog);
        }
    }
}
