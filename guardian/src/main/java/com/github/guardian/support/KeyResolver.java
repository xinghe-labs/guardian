package com.github.guardian.support;

import com.github.guardian.exception.GuardianException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.StringUtils;

/**
 * 把注解上的 SpEL 表达式解析成实际的 key。
 *
 * <p>支持的写法（与 Spring {@code @Cacheable} 的 SpEL 语义保持一致，用户可以直接迁移习惯）：
 * <ul>
 *   <li>{@code #req.orderNo} —— 按参数名取属性，<b>依赖编译时 {@code -parameters}</b></li>
 *   <li>{@code #a0} —— 按位置取参数，参数名拿不到时的逃生口</li>
 *   <li>{@code #root.methodName} / {@code #root.target} / {@code #root.args[0]} —— 方法上下文</li>
 *   <li>{@code 'order:' + #req.orderNo} —— 字面量拼接</li>
 * </ul>
 *
 * <p><b>{@code #root} 是方法上下文，不是被调用的那个 bean。</b>这一点容易搞错：
 * 直接 {@code new MethodBasedEvaluationContext(target, ...)} 时 {@code #root} 就是 target，
 * 于是 {@code #root.methodName} 会去 target 上找 {@code getMethodName()} 而失败。
 * 要拿到 bean 请用 {@code #root.target} —— 这正是 Spring Cache 的语义。
 */
public class KeyResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 解析表达式。
     *
     * @throws GuardianException 表达式为空、语法错误、求值为空、或参数名不可用
     */
    public String resolve(String expression, Method method, Object[] args, Object target) {
        if (!StringUtils.hasText(expression)) {
            throw new GuardianException("key 表达式不能为空");
        }

        Expression parsed;
        try {
            parsed = expressionCache.computeIfAbsent(expression, PARSER::parseExpression);
        } catch (RuntimeException e) {
            throw new GuardianException(
                    "key 表达式语法错误: \"" + expression + "\"（方法 " + method.getName() + "）", e);
        }

        GuardianEvaluationContext context =
                new GuardianEvaluationContext(target, method, args, nameDiscoverer);

        String value;
        try {
            value = parsed.getValue(context, String.class);
        } catch (RuntimeException e) {
            throw new GuardianException(
                    "key 表达式求值失败: \"" + expression + "\"，方法 " + method.getName()
                            + parameterNameHint(method, args),
                    e);
        }

        if (!StringUtils.hasText(value)) {
            // 静默放过等于没做幂等：所有请求算出同一个空 key，第一批之后就全被拒绝
            throw new GuardianException(
                    "key 表达式 \"" + expression + "\" 求值为空，方法 " + method.getName()
                            + "。通常是某个参数为 null —— 幂等/限流的 key 不允许为空，"
                            + "否则不同请求会撞到同一个 key");
        }
        return value;
    }

    /**
     * 参数名不可用时给一条能直接照做的提示。
     *
     * <p>这个错很常见（复制了别人的 pom 但没开 {@code -parameters}），
     * 而 SpEL 自己的报错是 {@code EL1007E: Property or field 'req' cannot be found}，
     * 看着像"字段名写错了"，实际是参数名没了。
     *
     * <p><b>不能一看到"名字找不到"就提示开 {@code -parameters}</b>：零参方法、
     * 或者真的写错了属性名（{@code #root.methodName} 误写成 {@code #root.methodNameX}）
     * 都会走到这里，乱给提示比不给更误导。所以只在<b>确实有参数但拿不到名字</b>时才提示。
     */
    private String parameterNameHint(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            // 零参方法本来就没有参数名可丢，不可能是 -parameters 的问题
            return "";
        }
        String[] names = nameDiscoverer.getParameterNames(method);
        boolean unusable = names == null || names.length < args.length;
        if (!unusable) {
            for (String n : names) {
                // 没开 -parameters 时 Spring 会退化成 arg0 / arg1
                if (n == null || n.startsWith("arg")) {
                    unusable = true;
                    break;
                }
            }
        }
        if (!unusable) {
            return "";
        }
        return "\n提示：该方法拿不到真实参数名（编译器没有保留），表达式里的 #参数名 无法解析。"
                + "请在 pom.xml 的 maven-compiler-plugin 中开启 <parameters>true</parameters>"
                + " 后重新编译；或改用位置写法 #a0 / #p0。";
    }

    /** 仅供测试：当前缓存了多少条表达式。 */
    int cachedExpressionCount() {
        return expressionCache.size();
    }

    /**
     * 把 {@code #root} 换成方法上下文视图。
     *
     * <p>只覆盖 {@code getRootObject()}，参数名变量（{@code #req}）仍由父类的
     * {@code lookupVariable} 提供，不受影响。
     */
    private static final class GuardianEvaluationContext extends MethodBasedEvaluationContext {

        private final MethodRoot root;

        GuardianEvaluationContext(Object target, Method method, Object[] args,
                                  ParameterNameDiscoverer discoverer) {
            super(target, method, args, discoverer);
            this.root = new MethodRoot(method, target, args);
        }

        @Override
        public TypedValue getRootObject() {
            return new TypedValue(root);
        }
    }

    /** {@code #root} 暴露的内容，与 Spring Cache 的 SpEL 上下文对齐。 */
    public static final class MethodRoot {

        private final Method method;
        private final Object target;
        private final Object[] args;

        MethodRoot(Method method, Object target, Object[] args) {
            this.method = method;
            this.target = target;
            this.args = args;
        }

        public String getMethodName() {
            return method.getName();
        }

        public Method getMethod() {
            return method;
        }

        public Object getTarget() {
            return target;
        }

        public Class<?> getTargetClass() {
            return target != null ? target.getClass() : method.getDeclaringClass();
        }

        public Object[] getArgs() {
            return args;
        }
    }
}
