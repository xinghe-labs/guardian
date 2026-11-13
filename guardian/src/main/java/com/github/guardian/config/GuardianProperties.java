package com.github.guardian.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code guardian.*} 配置。
 *
 * <p><b>为什么没有 {@code guardian.redis.*}</b>：Redis 连接直接复用 Spring Boot 的
 * {@code spring.data.redis.*}。再造一套会有两个后果 —— 配置重复（用户改一处不生效），
 * 更糟的是<b>可能建出第二个连接工厂</b>，连接池翻倍。Starter 的职责是"在用户已有的
 * Redis 上做增强"，不是自带 Redis。
 *
 * <p><b>{@code ignoreUnknownFields = false} 是刻意开的</b>：默认 {@code true} 时字段名拼错
 * （如 {@code default-tll}）会被<b>静默丢弃、全走兜底值</b>，启动正常但配置完全没生效。
 * 开启后拼错会启动失败。代价是这个报错<b>不告诉你是哪个字段错了</b>（实测消息只有
 * {@code Could not bind properties to 'GuardianProperties' : prefix=guardian, ...}），
 * 所以它的价值是"把静默失效变成能感知到失效"，仍需二分排查。
 *
 * <p>纯数字无单位那条修不掉：{@code default-ttl: 10} 会被 {@code Duration} 绑定规则
 * 解释成 <b>10 毫秒</b>（不是 10 秒）。所以 {@link #validate()} 在启动时拦一道，
 */
@ConfigurationProperties(prefix = "guardian", ignoreUnknownFields = false)
public class GuardianProperties {

    /** 总开关。false 时不注册任何切面。 */
    private boolean enabled = true;

    /** 所有 Redis key 的统一前缀，便于与其他应用隔离。 */
    private String keyPrefix = "guardian:";

    private final Idempotent idempotent = new Idempotent();
    private final RateLimit rateLimit = new RateLimit();
    private final Lock lock = new Lock();

    /**
     * 启动时兜底校验。
     *
     * <p>只拦 {@code < 1s} 的 ttl 与 lease：这是"忘了写单位"的典型后果
     * （{@code default-ttl: 10} &rarr; 10ms，幂等窗口从 10 秒变成 0.01 秒，<b>等于没做幂等</b>）。
     *
     * <p>刻意<b>不</b>校验 {@code default-window}：10ms 的限流窗口虽然多半是笔误，
     * 但高 QPS 场景下是合法配置，无法与笔误区分，硬拦会误伤。
     */
    @PostConstruct
    void validate() {
        requireAtLeastOneSecond("guardian.idempotent.default-ttl", idempotent.getDefaultTtl());
        requireAtLeastOneSecond("guardian.lock.default-lease", lock.getDefaultLease());
        if (rateLimit.getDefaultPermits() <= 0) {
            throw new IllegalStateException(
                    "guardian.rate-limit.default-permits 必须为正数，当前为 " + rateLimit.getDefaultPermits());
        }
        if (lock.getWatchdogRenewInterval().isNegative() || lock.getWatchdogRenewInterval().isZero()) {
            throw new IllegalStateException(
                    "guardian.lock.watchdog-renew-interval 必须为正数，当前为 " + lock.getWatchdogRenewInterval());
        }
    }

    private static void requireAtLeastOneSecond(String name, Duration value) {
        if (value.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException(name + " 必须 >= 1s，当前为 " + value
                    + "（提示：纯数字会被当成毫秒。'10' = 10ms，'10s' 才是 10 秒）");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Idempotent getIdempotent() {
        return idempotent;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Lock getLock() {
        return lock;
    }

    /** 幂等配置。 */
    public static class Idempotent {

        /** 占位有效期。 */
        private Duration defaultTtl = Duration.ofMinutes(10);

        /**
         * Redis 不可用时的行为。
         *
         * <p>默认 <b>false（fail-closed）</b>：Redis 挂了还放行 = 重复执行。
         * 重复扣款 / 重复下单的代价远大于"暂时不可用"。
         */
        private boolean failOpen = false;

        public Duration getDefaultTtl() {
            return defaultTtl;
        }

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }
    }

    /** 限流配置。 */
    public static class RateLimit {

        /** 窗口内默认允许的请求数。 */
        private int defaultPermits = 10;

        /** 默认窗口长度。 */
        private Duration defaultWindow = Duration.ofSeconds(1);

        /**
         * Redis 不可用时的行为。
         *
         * <p>默认 <b>true（fail-open）</b>：Redis 挂了还拒绝 = 全站 502。
         * 限流失效只是短时过载，比全挂好得多。
         */
        private boolean failOpen = true;

        public int getDefaultPermits() {
            return defaultPermits;
        }

        public void setDefaultPermits(int defaultPermits) {
            this.defaultPermits = defaultPermits;
        }

        public Duration getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(Duration defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }
    }

    /** 分布式锁配置。 */
    public static class Lock {

        /** 默认租期。注解 {@code leaseTime} 显式写正数时被覆盖。 */
        private Duration defaultLease = Duration.ofSeconds(30);

        /** 是否开启看门狗续期。false 时租期是硬上限，超时即丢锁。 */
        private boolean watchdogEnabled = true;

        /**
         * 续期间隔的<b>上限</b>（不是定值）。
         *
         * <p>实际间隔 = {@code min(本值, 生效租期 / 3)}，见 {@link #effectiveRenewInterval(Duration)}。
         * 默认 10s 是配 {@code default-lease 30s} 的（10s = 30s/3，与 Redisson 看门狗同口径）。
         */
        private Duration watchdogRenewInterval = Duration.ofSeconds(10);

        /**
         * Redis 不可用时的行为。
         *
         * <p>默认 <b>false（fail-closed）</b>：拿不到锁就放行 = 锁形同虚设，并发写会互相覆盖数据。
         */
        private boolean failOpen = false;

        /**
         * 算出实际续期间隔。
         *
         * <pre>
         * 实际间隔 = min(watchdog-renew-interval, 生效租期 / 3)
         * </pre>
         *
         * <p>为什么要取 min 而不是直接用配置值：租期<b>不完全来自配置</b>。
         * 注解写 {@code leaseTime = 5s} 时真实租期是 5s，而配置的续期间隔仍是 10s ——
         * <b>锁在 t=5s 就过期了，续期线程还没跑到</b>。
         *
         * <p>一句可解释的规则：<b>续期永远不超过租期的三分之一</b>。
         * 这样留出 2 次容错，单次续期被 Redis 慢查询拖住也不会丢锁。
         *
         * @param effectiveLease 已解析的生效租期（注解值或 default-lease）
         * @return 实际调度间隔
         */
        public Duration effectiveRenewInterval(Duration effectiveLease) {
            Duration third = effectiveLease.dividedBy(3);
            if (third.isNegative() || third.isZero()) {
                // 租期短到 1/3 不足 1 纳秒时，退化为 1ms，避免调度间隔为 0 变成忙等
                third = Duration.ofMillis(1);
            }
            return watchdogRenewInterval.compareTo(third) <= 0 ? watchdogRenewInterval : third;
        }

        public Duration getDefaultLease() {
            return defaultLease;
        }

        public void setDefaultLease(Duration defaultLease) {
            this.defaultLease = defaultLease;
        }

        public boolean isWatchdogEnabled() {
            return watchdogEnabled;
        }

        public void setWatchdogEnabled(boolean watchdogEnabled) {
            this.watchdogEnabled = watchdogEnabled;
        }

        public Duration getWatchdogRenewInterval() {
            return watchdogRenewInterval;
        }

        public void setWatchdogRenewInterval(Duration watchdogRenewInterval) {
            this.watchdogRenewInterval = watchdogRenewInterval;
        }

        public boolean isFailOpen() {
            return failOpen;
        }

        public void setFailOpen(boolean failOpen) {
            this.failOpen = failOpen;
        }
    }
}
