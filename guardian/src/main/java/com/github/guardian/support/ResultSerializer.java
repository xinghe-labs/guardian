package com.github.guardian.support;

import java.lang.reflect.Type;

/**
 * 业务返回值的序列化 SPI。
 *
 * <p>为什么抽成接口：{@code cacheResult = true} 需要把返回值存进 Redis，
 * 但<b>序列化方式不该由框架替用户决定</b>。Jackson 不是 starter 的必需依赖
 * （{@code spring-boot-starter-aop} 不带它），所以：
 * <ul>
 *   <li>classpath 上有 Jackson &rarr; 用 {@link JacksonResultCodec}（绝大多数 Spring Web 项目）</li>
 *   <li>没有 &rarr; 用 {@link UnavailableResultCodec}，只有真正用了
 *       {@code cacheResult = true} 才报错，不影响只做占位的用法</li>
 *   <li>用户自己声明一个 {@code ResultSerializer} bean &rarr; 完全接管</li>
 * </ul>
 * 这比"把 Jackson 设成强制依赖"温和：一个只想用分布式锁的项目不该被迫引入 Jackson。
 */
public interface ResultSerializer {

    /** 序列化业务返回值。 */
    String serialize(Object value);

    /** 按方法声明的返回类型反序列化。 */
    Object deserialize(String json, Type targetType);
}
