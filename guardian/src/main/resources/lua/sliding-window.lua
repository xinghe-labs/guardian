-- 滑动窗口限流
-- KEYS[1] = 限流 key（ZSET，score = 毫秒时间戳，member = 请求唯一标识）
-- ARGV[1] = now（毫秒，由客户端传入，不用 redis.call('time')（TIME 是非确定性命令，Redis 5.0 之前不允许其后再写命令））
-- ARGV[2] = 窗口长度（毫秒）
-- ARGV[3] = 窗口内允许的请求数 permits
-- ARGV[4] = 本次请求的唯一标识（必须每次不同，否则 ZADD 会去重导致计数偏小）
--
-- 返回：本次请求在窗口内的序号（1-based）。
--   返回值 <= permits 表示放行且已记录；> permits 表示拒绝且【未记录】
--   —— 拒绝的请求不落库，否则洪水攻击会把 ZSET 撑大，且合法用户被挤出窗口
local now    = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

-- 左开右闭区间 (now - window, now]：score <= now - window 的记录已滑出
redis.call('zremrangebyscore', KEYS[1], 0, now - window)

local current = redis.call('zcard', KEYS[1])
local ordinal = current + 1

if ordinal > permits then
    return ordinal
end

redis.call('zadd', KEYS[1], now, ARGV[4])
redis.call('pexpire', KEYS[1], window)
return ordinal
