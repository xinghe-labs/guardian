package com.github.guardian.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.guardian.exception.GuardianException;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结果序列化 SPI 的测试。
 *
 * <p>这两个实现共 18 行，覆盖率检查时只有 33% / 40%——因为没人测失败路径。
 * 但失败路径恰恰决定了"返回值序列化不了的时候，用户会看到什么"：
 * 是含类型名的可诊断异常，还是一句 `NullPointerException`。
 *
 * <p>另外这里有一条设计上的取舍需要实测确认：序列化失败被抛成
 * {@link GuardianException}（RuntimeException）后，会落进 {@code IdempotentAspect}
 * 的 catch 分支 —— 于是占位被释放、业务已经执行过却允许重试。
 * 见 ResultCodec 的"序列化失败"路径用例。
 */
class ResultCodecTest {

    record OrderResult(String status) {
    }

    /** 自引用对象：Jackson 会报 infinite recursion，用来触发序列化失败。 */
    static class Cyclic {
        @SuppressWarnings("unused")
        public Cyclic self = this;
    }

    private final JacksonResultCodec codec = new JacksonResultCodec(null);

    // ---- JacksonResultCodec ----

    @Test
    @DisplayName("serialize(null) 存成字面量 null，不是 '{}' 也不是空串")
    void serializeNull() {
        // 为什么单独一条：如果返回 "null" 以外的值，反序列化时要么报错、要么静默变成 null，
        // 而 cacheResult=true 的语义是"返回当时的结果"——null 也是合法结果
        assertThat(codec.serialize(null)).isEqualTo("null");
        assertThat(codec.deserialize("null", OrderResult.class)).isNull();
    }

    @Test
    @DisplayName("序列化失败：抛 GuardianException，信息里带类型名和解法")
    void serializeFailureIsDiagnosable() {
        assertThatThrownBy(() -> codec.serialize(new Cyclic()))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("序列化失败")
                .hasMessageContaining("Cyclic")
                .hasMessageContaining("cacheResult");
    }

    @Test
    @DisplayName("反序列化失败：抛 GuardianException，信息里带目标类型和内容片段")
    void deserializeFailureIsDiagnosable() {
        assertThatThrownBy(() -> codec.deserialize("{not-json", OrderResult.class))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("反序列化失败")
                .hasMessageContaining("OrderResult")
                .hasMessageContaining("{not-json");
    }

    @Test
    @DisplayName("超长内容在错误信息里被截断到 120 字符，避免日志被整个响应体刷屏")
    void longContentIsAbbreviated() {
        String longJson = "{invalid-" + "x".repeat(300) + "}";

        assertThatThrownBy(() -> codec.deserialize(longJson, OrderResult.class))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("...")
                .satisfies(e -> assertThat(e.getMessage().length())
                        .as("截断没生效的话错误信息会有 300+ 字符")
                        .isLessThan(300));
    }

    @Test
    @DisplayName("泛型返回值：按 getGenericReturnType 还原元素类型，不退化成 Map")
    void genericTypeIsPreserved() throws Exception {
        // 这是"为什么用 getGenericReturnType 而不是 getReturnType"的实测证据：
        // 传 getReturnType() 的话元素会变成 LinkedHashMap，下面的 isInstanceOf 会挂
        Type real = Holder.class.getMethod("list").getGenericReturnType();

        String json = codec.serialize(List.of(new OrderResult("A")));
        Object back = codec.deserialize(json, real);

        assertThat(back).isInstanceOf(List.class);
        assertThat((List<?>) back).first().isInstanceOf(OrderResult.class);
    }

    /** 只为拿一个 {@code List<OrderResult>} 的泛型类型。 */
    static class Holder {
        public List<OrderResult> list() {
            return List.of();
        }
    }

    // ---- UnavailableResultCodec ----

    @Test
    @DisplayName("没有 Jackson 时：两个方法都抛，且错误信息给出三种解法")
    void unavailableCodecExplainsAllOptions() {
        UnavailableResultCodec fallback = new UnavailableResultCodec();

        assertThatThrownBy(() -> fallback.serialize(new OrderResult("A")))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("jackson-databind")
                .hasMessageContaining("ResultSerializer")
                .hasMessageContaining("cacheResult");

        assertThatThrownBy(() -> fallback.deserialize("{}", OrderResult.class))
                .isInstanceOf(GuardianException.class);
    }
}
