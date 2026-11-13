# guardian

幂等 / 限流 / 分布式锁 三合一 Spring Boot Starter。三个注解，零配置可用，Redis 缺失时自动降级。

[![Release](https://jitpack.io/v/xinghe-labs/guardian.svg)](https://jitpack.io/#xinghe-labs/guardian/v0.1.1)
[![CI](https://github.com/xinghe-labs/guardian/actions/workflows/ci.yml/badge.svg)](https://github.com/xinghe-labs/guardian/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Coverage](https://img.shields.io/badge/coverage-93%25-brightgreen.svg)](guardian/pom.xml)

```java
@Idempotent(key = "'order:' + #req.id")              // 同一订单只提交一次
@RateLimit(key = "#req.userId", permits = 10)         // 每用户每秒 10 次
@DistributedLock(key = "'stock:' + #sku")             // 跨实例互斥
public OrderResult createOrder(CreateOrderReq req) { ... }
```

Spring Boot 3.x / JDK 17。

## 仓库结构

```text
guardian/        starter 本体（三个注解 + AOP 切面 + Local/Redis 双存储 + 128 个测试）
guardian-demo/   消费方视角 demo（仅靠 Maven 坐标引入 starter 的真实 Spring Boot 工程）
jitpack.yml      JitPack 构建配置（仓库无聚合 pom，需指定构建 guardian/）
```

详细文档（注解属性表、SpEL 写法、配置项、存储选择、已知限制）见 [guardian/README.md](guardian/README.md)。

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
  <version>v0.1.1</version>
</dependency>
```

`@EnableGuardian` 不需要 —— starter 自动装配。Redis 相关依赖是 `optional`，要用 Redis 存储请自行引入 `spring-boot-starter-data-redis`。

## 运行 demo

```bash
cd guardian && mvn install
cd ../guardian-demo && mvn spring-boot:run
```

demo 实测记录（详细步骤与预期响应见 [guardian-demo/README.md](guardian-demo/README.md)）：

- 幂等：同一 `orderNo` 提交两次返回**同一个 orderId**（`cacheResult` 经 Jackson 编解码）
- 限流：5 连发 `200 200 200 429 429`
- 锁：并发抢同一库存 `423 + 200`
- Redis 实际 key：`demo:idem:A001`（`key-prefix: demo:` 生效）

## 构建

```bash
cd guardian
mvn test      # 无 Redis 时自动跳过 Redis 相关测试（Assumptions）
mvn verify    # 含覆盖率检查（行覆盖率门槛见 pom）
```

测试分层、契约测试与失败路径测试的说明见 [guardian/README.md](guardian/README.md) 的「开发」一节。

## License

MIT
