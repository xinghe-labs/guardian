# guardian

幂等 / 限流 / 分布式锁 三合一 Spring Boot Starter。三个注解，零配置可用，Redis 缺失时自动降级。

[![Release](https://jitpack.io/v/xinghe-labs/guardian.svg)](https://jitpack.io/#xinghe-labs/guardian/v0.1.0)

```java
@Idempotent(key = "'order:' + #req.id")              // 同一订单只提交一次
@RateLimit(key = "#req.userId", permits = 10)         // 每用户每秒 10 次
@DistributedLock(key = "'stock:' + #sku")             // 跨实例互斥
public OrderResult createOrder(CreateOrderReq req) { ... }
```

Spring Boot 3.x / JDK 17。

---

## 为什么不是"再写一个工具类"

业务代码里手写 `redisTemplate.opsForValue().setIfAbsent(...)` 有三个反复出现的坑，这个 starter 把它们收进切面：

| 坑 | 手写时常见写法 | 后果 | 本项目的处理 |
|---|---|---|---|
| 锁误删 | 业务结束直接 `DEL` | GC 停顿超过租期后，A 删掉 B 刚拿到的锁 | Lua CAS：`GET == token` 才 `DEL` |
| 锁续期 | 固定 10s 续一次 | 租期 5s 时，续期还没发生锁就过期了 | 实际间隔 = `min(配置值, 生效租期/3)` |
| 幂等失败后锁死 | 业务抛异常也留着占位 | 用户重试永远被拒 | 默认 `onException = RELEASE`，失败立即释放 |

还有一条不在代码里、但最容易踩的：**滑动窗口的 Lua 不能用 `redis.call('time')`**。`TIME` 是非确定性命令，Redis 5.0 之前不允许"非确定性命令之后再写"，会直接报 `ERR Write commands not allowed after non deterministic commands`。所以时间戳由客户端作为 `ARGV` 传入。代价是多实例时钟偏差，收益是兼容 Redis 3.0 且行为可测（12 次请求严格放行 10 次）。

---

## 快速开始

JitPack 已实测可拉（构建日志 + 空仓库消费方编译均已验证）：

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.xinghe-labs</groupId>
  <artifactId>guardian</artifactId>
  <version>v0.1.0</version>
</dependency>
```

`@EnableGuardian` 不需要 —— starter 自动装配。Redis 相关依赖是 `optional`，要用 Redis 存储请自行引入：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

## 三个注解

### `@DistributedLock`

```java
@DistributedLock(key = "'stock:' + #sku", waitTime = 3, unit = TimeUnit.SECONDS)
public void deduct(String sku, int count) { ... }
```

| 属性 | 默认 | 说明 |
|---|---|---|
| `key` | 必填 | SpEL 表达式 |
| `waitTime` | `3` | 拿锁最长等待；超时抛 `LockAcquireFailedException` |
| `leaseTime` | `-1` | `-1` = 开启看门狗自动续期；正数 = 固定租期，不续期 |
| `unit` | `SECONDS` | `waitTime` / `leaseTime` 的单位 |

`leaseTime = -1` 时看门狗按 `min(watchdog-renew-interval, 生效租期/3)` 续期。**`/3` 不是拍脑袋**：续期至少要比租期快一次"失败重试"的余量，取 1/3 能容忍两次续期失败而不丢锁。

### `@Idempotent`

```java
@Idempotent(key = "'order:' + #req.id", cacheResult = true, ttl = 30, unit = TimeUnit.MINUTES)
public OrderResult create(CreateOrderReq req) { ... }
```

一个 key 有三个阶段，第二个请求必然落进其中之一：

| 阶段 | Redis 里的值 | `onPending = FAIL`（默认） | `onPending = SPIN` |
|---|---|---|---|
| 首次 | `PENDING` | 正常执行 | 正常执行 |
| 进行中 | `PENDING`（别人占的） | 抛 `DuplicateRequestException` | 自旋等 `pendingSpinMillis`，等到就返结果，超时仍抛 |
| 已完成 | `DONE:{...}` | 抛 `DuplicateRequestException` | `cacheResult=true` 时直接返回缓存结果 |

| 属性 | 默认 | 说明 |
|---|---|---|
| `key` | 必填 | SpEL 表达式，解析结果直接当业务 key（**不哈希**，便于在 `redis-cli` 里定位） |
| `ttl` | `-1` | `-1` = 用 `guardian.idempotent.default-ttl` |
| `mode` | `FINGERPRINT` | `TOKEN` 未实现，传入抛 `UnsupportedOperationException`（不留"配了不生效"的坑） |
| `cacheResult` | `false` | 是否回填并返回首次执行的返回值（需要 Jackson） |
| `onPending` | `FAIL` | `FAIL` / `SPIN` |
| `pendingSpinMillis` | `0` | `SPIN` 的最大等待 |
| `onException` | `RELEASE` | `RELEASE` 释放占位允许立即重试；`KEEP` 保留到 ttl |

`onException` 默认值为什么是 `RELEASE`：失败请求本就没做成，留着占位等于禁止用户重试。**幂等在失败路径上比成功路径更容易出错**——只测"并发 50 次只执行 1 次"是不够的。

### `@RateLimit`

```java
@RateLimit(key = "#req.userId", permits = 10, window = 1, unit = TimeUnit.SECONDS)
public List<Item> list(QueryReq req) { ... }
```

滑动窗口（ZSET + Lua），窗口语义是**左开右闭** `(now - window, now]`。超限抛 `RateLimitExceededException`，异常里带 `Retry-After` 秒数。

`strategy` 支持 `REJECT`（默认）和 `THROTTLE`。

## SpEL 写法

| 写法 | 示例 |
|---|---|
| 参数名 | `#req.id`（需要编译带 `-parameters`，Boot 官方 parent 已默认开启） |
| 位置 | `#a0.id` / `#p1` |
| 方法信息 | `#root.methodName`、`#root.target`、`#root.args` |
| 字面量拼接 | `'order:' + #req.id` |

参数名取不到时**启动即失败**并提示开启 `-parameters`，而不是运行时解析成 `null` 悄悄生成一个假 key。

## 配置

```yaml
guardian:
  enabled: true
  key-prefix: "guardian:"
  idempotent:
    default-ttl: 10m
    fail-open: false        # Redis 故障时：false = 抛异常拒绝，true = 放行
  rate-limit:
    default-permits: 10
    default-window: 1s
    fail-open: true
  lock:
    default-lease: 30s
    watchdog-enabled: true
    watchdog-renew-interval: 10s
    fail-open: false
```

配置字段拼错会**启动失败**（`ignoreUnknownFields = false`），但 Spring 的报错不点名是哪个字段 —— 这是已知短板，排查时对照上面这张表。

`fail-open` 的默认值不是随手定的：限流挂了大不了多放过几个请求（放行），幂等挂了会导致重复下单（拒绝）。

## 存储

| 实现 | 何时用 | 多实例 |
|---|---|---|
| `LocalGuardianStorage` | 单机 / 测试，无需 Redis | ❌ 各实例独立计数，**不是分布式** |
| `RedisGuardianStorage` | 生产，classpath 有 Redis 就自动选中 | ✅ |

选择由 `@ConditionalOnMissingBean` 决定：classpath 有 `StringRedisTemplate` 就用 Redis，否则退回本地。**注意**：Spring 的 bean 覆盖顺序不是声明顺序能保证的，本项目用 `@AutoConfiguration(before/after)` 显式排定，不依赖嵌套配置类的书写位置。

## 已知限制

- **单节点 Redis**：Lua 脚本假定所有 `KEYS[]` 可原子访问，不支持 cluster 的多 slot 场景。
- **Redis ≥ 3.0**：已在 **3.0.504** 上验证全部脚本（特意用这个 2015 年的版本跑，证明没有依赖 5.0 才有的能力）。
- **`cacheResult` 需要 Jackson**：classpath 没有 `jackson-databind` 时自动退回 `UnavailableResultCodec`，**只在用到时报错**，不影响启动。
- **`TOKEN` 模式未实现**：传入即抛，不做"静默降级成 FINGERPRINT"。

## 开发

```bash
mvn test          # 无 Redis 时自动跳过 Redis 相关测试（Assumptions）
mvn verify        # 含覆盖率检查（行覆盖率门槛见 pom）
```

测试分层：

- **契约测试** `GuardianStorageContractTest`：抽象类，同一套 12 条不变量对 `LocalGuardianStorage` 和 `RedisGuardianStorage` 各跑一遍。任一实现跑偏立刻分叉 —— 这是拦住"测试环境通过、换存储上线出错"的唯一手段。
- **Redis 测试**：对着真实 Redis 跑，不用 mock。要验的是 TTL 有没有真设上、Lua 是不是原子、ZSET 边界对不对，mock 只能验"我调了哪个方法"。
- **失败路径测试** `IdempotentAspectFailureTest`：Redis 超时、锁竞争、自旋被中断、序列化失败。

两条最重要的并发不变量有专门用例守着：`setIfAbsent` 50 线程**有且只有一个赢家**；`incrementInWindow` 50 线程返回值**互不重复**。这两条就是"为什么不能 GET 再 SET / GET 再 INCR"的可执行证据。

## License

MIT
