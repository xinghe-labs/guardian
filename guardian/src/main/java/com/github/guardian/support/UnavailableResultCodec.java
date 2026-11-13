package com.github.guardian.support;

import com.github.guardian.exception.GuardianException;
import java.lang.reflect.Type;

/**
 * classpath 上没有 Jackson 时的兜底实现。
 *
 * <p><b>为什么不在启动时就报错</b>：只想用 {@code @DistributedLock} 或
 * {@code cacheResult = false} 的项目根本不需要序列化。启动即失败等于强迫所有人引 Jackson。
 * 所以延迟到<b>真正用到</b> {@code cacheResult = true} 时才抛，并且错误信息直接给出解法。
 */
public class UnavailableResultCodec implements ResultSerializer {

    private static final String HINT =
            "当前 classpath 上没有 Jackson（com.fasterxml.jackson.databind.ObjectMapper），"
                    + "但 @Idempotent(cacheResult = true) 需要把返回值序列化后存进 Redis。"
                    + "三种解法任选其一：① 引入 com.fasterxml.jackson.core:jackson-databind；"
                    + "② 自己声明一个 ResultSerializer bean；"
                    + "③ 把该注解的 cacheResult 关掉（重复请求仍然会被拦截，只是不返回缓存结果）。";

    @Override
    public String serialize(Object value) {
        throw new GuardianException(HINT);
    }

    @Override
    public Object deserialize(String json, Type targetType) {
        throw new GuardianException(HINT);
    }
}
