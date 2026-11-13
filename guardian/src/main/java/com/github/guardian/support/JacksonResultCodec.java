package com.github.guardian.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guardian.exception.GuardianException;
import java.lang.reflect.Type;

/**
 * 基于 Jackson 的 {@link ResultSerializer} 实现 —— classpath 上有 Jackson 时的默认选择。
 *
 * <p>为什么不用 Java 原生序列化：它要求返回值类型 {@code implements Serializable}，
 * 而且序列化结果是二进制，在 {@code redis-cli} 里看到的是乱码 —— 排查
 * "这个 key 里到底存了什么" 时非常痛苦。JSON 直接 {@code GET} 就能读。
 *
 * <p>反序列化用 {@code getGenericReturnType()} 而不是 {@code getReturnType()}：
 * 返回 {@code List<OrderVO>} 时后者只剩 {@code List}，元素会变成 {@code LinkedHashMap}。
 */
public class JacksonResultCodec implements ResultSerializer {

    private final ObjectMapper mapper;

    public JacksonResultCodec(ObjectMapper mapper) {
        // findAndRegisterModules()：自动装上 classpath 上有的模块（如 JavaTimeModule）。
        // 不这么做的话，返回 LocalDateTime 的业务方法在 cacheResult=true 时会在运行期才报
        // "Java 8 date/time type not supported by default"，而且只在缓存回填那一步报
        this.mapper = mapper != null ? mapper : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public String serialize(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new GuardianException(
                    "幂等结果序列化失败，类型 " + value.getClass().getName()
                            + "。cacheResult=true 时返回值必须能被 Jackson 序列化；"
                            + "若返回类型不可序列化，请把 @Idempotent(cacheResult) 关掉", e);
        }
    }

    @Override
    public Object deserialize(String json, Type targetType) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructType(targetType));
        } catch (Exception e) {
            throw new GuardianException(
                    "幂等结果反序列化失败，目标类型 " + targetType.getTypeName()
                            + "，内容 " + abbreviate(json), e);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }
}
