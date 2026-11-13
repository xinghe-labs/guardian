package com.github.guardian.enums;

/**
 * 首次请求还在执行（key 存在且值为 {@code PENDING}）时，重复请求怎么处理。
 *
 * <p>注意"三态"是靠两态 + key 是否存在表达的：key 不存在 = 没做过；
 * key = {@code PENDING} = 正在做；key = {@code DONE:...} = 做完了。
 * 不额外建状态字段。
 */
public enum PendingStrategy {

    /** 直接拒绝，抛 {@code DuplicateRequestException}。 */
    FAIL,

    /** 自旋等待至多 {@code pendingSpinMillis} 毫秒，期间若变 {@code DONE} 则返回其结果。 */
    SPIN
}
