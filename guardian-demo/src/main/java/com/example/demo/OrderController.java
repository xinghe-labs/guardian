package com.example.demo;

import com.github.guardian.annotation.DistributedLock;
import com.github.guardian.annotation.Idempotent;
import com.github.guardian.annotation.RateLimit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三个注解各演示一次，再加一个三注解叠加端点。
 *
 * <p>切面顺序是 starter 内部约定（@Order 10/20/30）：限流 → 判重 → 锁。
 * 本类不需要写任何顺序相关代码——这也是 starter 化的意义。
 */
@RestController
public class OrderController {

    /**
     * 三注解叠加的"提交订单"：先限流（防打满 Redis），再判重（防重复提交），
     * 后拿锁（同一订单号串行处理）。cacheResult = true：重复请求直接返回首次结果。
     */
    @PostMapping("/order")
    @RateLimit(key = "'order:submit'", permits = 20, window = 1)
    @Idempotent(key = "#req.orderNo", cacheResult = true, message = "订单正在提交，请勿重复点击")
    @DistributedLock(key = "#req.orderNo", waitTime = 0, message = "订单处理中")
    public Map<String, Object> submit(@RequestBody OrderRequest req) throws InterruptedException {
        Thread.sleep(300); // 模拟业务耗时，让并发演示窗口可见
        return Map.of(
                "orderNo", req.orderNo(),
                "orderId", UUID.randomUUID().toString(),
                "amount", req.amount(),
                "biz", "processed-once");
    }

    /** 限流独立演示：全局 10 秒 3 次，第 4 次起 429。 */
    @GetMapping("/limited")
    @RateLimit(key = "'global'", permits = 3, window = 10, unit = TimeUnit.SECONDS)
    public Map<String, Object> limited() {
        return Map.of("ok", true, "note", "window=10s permits=3");
    }

    /** 分布式锁独立演示：同一 taskId 串行，第二个并发请求拿不到锁立即失败。 */
    @PostMapping("/task/{id}")
    @DistributedLock(key = "#id", waitTime = 0, leaseTime = 10)
    public Map<String, Object> task(@PathVariable String id) throws InterruptedException {
        Thread.sleep(1500); // 持有足够久，两个并发 curl 必然撞上
        return Map.of("taskId", id, "status", "done");
    }
}
