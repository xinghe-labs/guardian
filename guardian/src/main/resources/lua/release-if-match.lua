-- 仅当 value 匹配时才删除（防止误删别人的锁）
-- KEYS[1] = 锁/占位 key
-- ARGV[1] = 期望的持有者标识
-- 返回 1 表示删除成功，0 表示 value 不匹配（已不属于自己，或已过期）
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
