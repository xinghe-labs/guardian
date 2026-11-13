# guardian-demo

消费方视角的演示工程：**只靠 Maven 坐标引入 starter**（不含任何 guardian 源码依赖），验证"引入即用"。

## 运行

前置：本地 Redis（默认连 `127.0.0.1:6399`，可在 `application.yml` 改；没有的话 `docker run -d -p 6399:6379 redis:3`）。

```bash
cd guardian && mvn install
cd ../guardian-demo && mvn spring-boot:run
```

不发请求直接启动也可以 —— 没有 Redis 时 starter 自动降级为本地存储，日志会打印当前用的实现。

## 试什么

### 1. 三注解叠加提交订单（`POST /order`）

```bash
curl -s -X POST http://localhost:18080/order \
  -H "Content-Type: application/json" \
  -d '{"orderNo":"A001","amount":99}'
# {"orderNo":"A001","orderId":"<uuid>","amount":99,"biz":"processed-once"}
```

| 重试方式 | 预期 |
|---|---|
| 换新 `orderNo` 再发 | 正常下单，新 orderId |
| **同 `orderNo` 立即重发** | `409`，message 为「订单正在提交，请勿重复点击」 |
| 同 `orderNo` 等 1 秒后重发（首次已回填） | `409`——重复请求被幂等拦截 |
| 1 秒内狂发 20+ 次 | `429`（限流先于幂等触发，切面顺序限流→判重→锁） |

### 2. 查询接口（`GET /order/{orderNo}`，演示限流）

```bash
for i in $(seq 1 6); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18080/order/A001; done
# 200 200 200 200 429 429   （permits=4/s；429 响应头带 Retry-After）
```

### 3. 看实际写入的 Redis key

```bash
redis-cli -p 6399 --scan --pattern 'demo:*'
# demo:idem:A001          ← 幂等占位/结果（消费方前缀 demo: 生效）
# demo:rl:order:submit    ← 滑动窗口 ZSET
# demo:lock:A001          ← 锁（仅持锁期间存在）
```

> 没有本地 Redis？`docker run -d -p 6399:6379 redis:3` 之后照常跑。

## 工程结构说明

- `ApiExceptionHandler`：把 starter 的三个异常映射为 HTTP 状态码（429/409/423），消费方只需要这一个 `@RestControllerAdvice`
- `application.yml`：只配了 `key-prefix: "demo:"`，其余全走 starter 默认值——零配置可用性的直接证据
