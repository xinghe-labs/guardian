package com.github.guardian.config;

import com.github.guardian.storage.GuardianStorage;
import com.github.guardian.storage.RedisGuardianStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 有 Redis 时注册 {@link RedisGuardianStorage}。
 *
 * <p><b>为什么存储要拆成两个顶层 auto-configuration，而不是主配置里的两个嵌套类</b>：
 * 嵌套 {@code @Configuration} 的处理顺序来自 {@code Class.getDeclaredClasses()}，
 * <b>该方法不保证返回源码声明顺序</b>（JVM 实现相关）。所以「把 Redis 写在前、Local 写在后，
 * 让 Local 的 {@code @ConditionalOnMissingBean} 看见 Redis」这种写法<b>不可靠</b> ——
 * 实测就出现了 Local 抢先注册、Redis 被 {@code @ConditionalOnMissingBean} 挡掉的情况，
 * 而错误是静默的：应用照常启动，只是多实例下"分布式锁"退化成了进程内锁。
 *
 * <p>可靠的做法是顶层 auto-configuration + {@code @AutoConfiguration(before/after)}：
 * 这个顺序由 Boot 的排序机制保证，与类加载顺序无关。
 */
@AutoConfiguration(
        after = RedisAutoConfiguration.class,
        before = GuardianLocalStorageAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
@ConditionalOnBean(StringRedisTemplate.class)
@ConditionalOnProperty(prefix = "guardian", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GuardianRedisStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuardianRedisStorageAutoConfiguration.class);

    /**
     * 用 {@code @ConditionalOnClass(name = ...)} 的字符串形式而不是 {@code Class} 字面量：
     * spring-data-redis 是 optional 依赖，用户没引时这个类不存在，
     * 用字面量会让条件注解本身在解析阶段就炸。
     */
    @Bean
    @ConditionalOnMissingBean
    public GuardianStorage guardianStorage(StringRedisTemplate redis) {
        log.info("guardian 使用 RedisGuardianStorage（分布式语义，可用于多实例）");
        return new RedisGuardianStorage(redis);
    }
}
