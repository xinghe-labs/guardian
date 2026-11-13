package com.github.guardian.exception;

/**
 * 在 waitTime 内没拿到锁。表示"业务方法从未开始执行"。
 *
 * <p>这个类型很重要：{@code @Idempotent} + {@code @DistributedLock} 叠加时，
 * 幂等切面已经写了 PENDING 占位，但业务根本没跑。若不复位占位，用户重试会被
 * 判"请勿重复提交"并持续到 ttl（默认 10 分钟）—— 
 */
public class LockAcquireFailedException extends GuardianException {

    public LockAcquireFailedException(String message) {
        super(message);
    }
}
