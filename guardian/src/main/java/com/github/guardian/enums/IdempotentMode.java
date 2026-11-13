package com.github.guardian.enums;

/**
 * 幂等 key 的生成方式。
 *
 * <p>MVP 只实现 {@link #FINGERPRINT}；{@link #TOKEN} 传入即抛
 * {@link UnsupportedOperationException}，不留"配了不生效"的坑。
 */
public enum IdempotentMode {

    /**
     * 业务指纹：由注解上的 SpEL 表达式解析出业务 key（如 {@code "order:#req.id"}），无需前端配合。
     *
     * <p><b>不做 md5</b>，故意保留可读性：出问题时能在 {@code redis-cli} 里直接
     * {@code KEYS guardian:idem:order:123} 定位，而不是面对一串哈希值反推是哪个请求。
     * 代价是 key 长度由业务字段决定——若某个字段可能很长（如整段 JSON），
     * 在表达式里取它的摘要（如 {@code #req.body.hashCode()}）而不是把整段塞进去。
     */
    FINGERPRINT,

    /** Token 预申请：前端先 GET /guardian/token，提交时带上。MVP 未实现。 */
    TOKEN
}
