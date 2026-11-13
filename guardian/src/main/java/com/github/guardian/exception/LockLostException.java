package com.github.guardian.exception;

/**
 * 业务执行期间锁已丢失（看门狗续期连续失败，锁因过期被别人拿走）。
 *
 * <p><b>这是事后检测，不是实时中断。</b>Java 没有可靠手段从续期线程打断业务线程：
 * {@code Thread.interrupt()} 只对 sleep/wait/阻塞 IO 生效，卡在 JDBC 查询或纯 CPU
 * 循环的业务完全不响应；{@code Thread.stop()} 已废弃且会撕裂事务。
 * 所以锁丢失窗口内已落库的写入不会被回滚 —— 真正兜住一致性要靠业务侧乐观锁/版本号。
 */
public class LockLostException extends GuardianException {

    public LockLostException(String message) {
        super(message);
    }
}
