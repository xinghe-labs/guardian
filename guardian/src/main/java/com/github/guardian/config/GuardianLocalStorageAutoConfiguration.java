package com.github.guardian.config;

import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.LocalGuardianStorage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 没有可用 Redis 时的兜底：注册 {@link LocalGuardianStorage}。
 *
 * <p>排在 {@link GuardianRedisStorageAutoConfiguration} <b>之后</b>，所以有 Redis 时
 * 它的 {@code @ConditionalOnMissingBean} 会看见 Redis bean 并让位。
 *
 * <p><b>必须告警而不是静默降级</b>：内存实现的锁只在单进程内有效，多实例部署时
 * 锁根本不存在，而且不会有任何报错。这是最容易上线后才发现的问题。
 */
@AutoConfiguration(after = GuardianRedisStorageAutoConfiguration.class)
@ConditionalOnProperty(prefix = "guardian", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GuardianLocalStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardianLocalStorageAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(GuardianStorage.class)
    public GuardianStorage guardianStorage(ObjectProvider<Clock> clock) {
        log.warn("guardian 未找到 StringRedisTemplate，回退到 LocalGuardianStorage。"
                + "状态存在进程内存中：单实例可用，多实例部署时锁/幂等/限流都不跨节点生效。"
                + "生产环境请引入 spring-boot-starter-data-redis 并配置 spring.data.redis.*");
        return new LocalGuardianStorage(clock.getIfAvailable(Clock::systemDefaultZone));
    }
}
