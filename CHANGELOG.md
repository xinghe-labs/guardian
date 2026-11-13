# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [v0.1.0] - 2026-05-12

### 新增

- `@Idempotent`：SpEL 业务指纹幂等，`SET NX` 占位 + 结果回填（`cacheResult`），`PENDING/DONE` 三态语义，失败路径 `RELEASE/KEEP` 策略
- `@RateLimit`：Lua 滑动窗口（ZSET），支持 `REJECT` / `THROTTLE`，超限异常带 `Retry-After`
- `@DistributedLock`：Lua CAS 释放（防误删），看门狗自动续期（`leaseTime = -1`）
- Local / Redis 双存储 SPI + 契约测试；Redis 缺失自动降级，classpath 探测自动装配
- `guardian-demo`：消费方视角演示工程，实测幂等 / 限流 / 锁行为
- JitPack 发布：`com.github.xinghe-labs:guardian:v0.1.0`

### 质量

- 128 个测试用例全绿（真实 Redis 集成、故障注入、并发不变量、契约测试）
- 行覆盖率 93%，JaCoCo 阈值绑定 `mvn verify`
