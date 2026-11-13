package com.github.guardian.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.guardian.exception.GuardianException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SpEL key 解析测试。
 *
 * <p>这里记的是"参数名拿不到"这个坑：不开 {@code -parameters} 时 {@code #req} 解析成
 * {@code null}，而且<b>不报错</b>——所有请求的 key 都一样，幂等退化成"全站只允许一次提交"。
 * 所以这里的重点是「解析不出来必须炸得清楚」，而不是「能解析出来」。
 */
class KeyResolverTest {

    private final KeyResolver resolver = new KeyResolver();

    // ---- 测试夹具：参数名必须靠 -parameters 保留 ----

    record OrderReq(String orderNo, Integer qty) {
    }

    static class Fixture {
        public void byParam(OrderReq req) {
        }

        public void noArg() {
        }

        public void byNullParam(OrderReq req) {
        }
    }

    private static Method method(String name, Class<?>... params) {
        try {
            return Fixture.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("按参数名取属性：#req.orderNo")
    void resolvesPropertyOfNamedParam() {
        Method m = method("byParam", OrderReq.class);
        String key = resolver.resolve("#req.orderNo", m, new Object[]{new OrderReq("A123", 2)}, new Fixture());
        assertThat(key).isEqualTo("A123");
    }

    @Test
    @DisplayName("同一表达式里拼接多个值，且数字会自动 toString")
    void concatenatesMultipleValues() {
        Method m = method("byParam", OrderReq.class);
        String key = resolver.resolve("#req.orderNo + ':' + #req.qty", m,
                new Object[]{new OrderReq("A123", 2)}, new Fixture());
        assertThat(key).isEqualTo("A123:2");
    }

    @Test
    @DisplayName("字面量表达式不需要任何参数")
    void literalExpression() {
        Method m = method("noArg");
        assertThat(resolver.resolve("'fixed-key'", m, new Object[0], new Fixture()))
                .isEqualTo("fixed-key");
    }

    @Test
    @DisplayName("#root.methodName 可用 —— 同一个 key 表达式复用到多个方法时靠它区分")
    void rootObjectIsAvailable() {
        Method m = method("noArg");
        assertThat(resolver.resolve("#root.methodName", m, new Object[0], new Fixture()))
                .isEqualTo("noArg");
    }

    @Test
    @DisplayName("#root 是方法上下文，不是 bean：#root.target 指向被调用的对象")
    void rootTargetIsTheBean() {
        Method m = method("noArg");
        Fixture target = new Fixture();
        // resolve() 返回 String，所以不能直接断言 #root.target 本身，
        // 而是验证它在表达式里能取到东西
        assertThat(resolver.resolve("#root.target.class.simpleName", m, new Object[0], target))
                .isEqualTo("Fixture");
        assertThat(resolver.resolve("#root.targetClass.simpleName", m, new Object[0], target))
                .isEqualTo("Fixture");
        assertThat(resolver.resolve("#root.args.length", m, new Object[0], target)).isEqualTo("0");
    }

    @Test
    @DisplayName("位置写法 #a0：参数名拿不到时的逃生口")
    void positionalParameterWorks() {
        Method m = method("byParam", OrderReq.class);
        assertThat(resolver.resolve("#a0.orderNo", m, new Object[]{new OrderReq("P9", 1)}, new Fixture()))
                .isEqualTo("P9");
    }

    @Test
    @DisplayName("解析结果为 null 时必须抛，不能返回 \"null\" 字符串")
    void nullResultThrows() {
        Method m = method("byNullParam", OrderReq.class);
        assertThatThrownBy(() -> resolver.resolve("#req.orderNo", m, new Object[]{null}, new Fixture()))
                .isInstanceOf(GuardianException.class)
                // 错误信息要带上表达式和方法，否则线上只看到一句"key 为空"没法定位
                .hasMessageContaining("#req.orderNo")
                .hasMessageContaining("byNullParam");
    }

    @Test
    @DisplayName("解析结果为空白同样抛 —— 空 key 会让所有请求撞同一个锁")
    void blankResultThrows() {
        Method m = method("byParam", OrderReq.class);
        assertThatThrownBy(() -> resolver.resolve("#req.orderNo", m,
                new Object[]{new OrderReq("   ", 1)}, new Fixture()))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("byParam");
    }

    @Test
    @DisplayName("表达式语法错误时抛，且指出是哪个表达式")
    void syntaxErrorMentionsExpression() {
        Method m = method("noArg");
        assertThatThrownBy(() -> resolver.resolve("#{", m, new Object[0], new Fixture()))
                .isInstanceOf(GuardianException.class)
                .hasMessageContaining("#{");
    }

    @Test
    @DisplayName("同一表达式重复解析走缓存，结果稳定")
    void cachedExpressionIsStable() {
        Method m = method("byParam", OrderReq.class);
        Fixture target = new Fixture();
        String first = resolver.resolve("#req.orderNo", m, new Object[]{new OrderReq("X1", 1)}, target);
        String second = resolver.resolve("#req.orderNo", m, new Object[]{new OrderReq("X2", 1)}, target);
        assertThat(first).isEqualTo("X1");
        assertThat(second).isEqualTo("X2");
    }
}
