package com.github.guardian.enums;

/**
 * 超出配额后的行为。
 */
public enum RateLimitStrategy {

    /** 直接拒绝，抛 {@code RateLimitExceededException}。MVP 只实现这个。 */
    REJECT,

    /** 阻塞至多 N 毫秒再决定拒绝（令牌桶）。MVP 未实现，传入即抛 UnsupportedOperationException。 */
    WAIT
}
