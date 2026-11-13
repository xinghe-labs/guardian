-- 续期：仅当 value 匹配时才重置过期时间
-- KEYS[1] = 锁 key
-- ARGV[1] = 期望的持有者标识
-- ARGV[2] = 新的过期时间（毫秒）
-- 返回 1 表示续期成功，0 表示锁已不属于自己（已过期或被别人抢走）
--
-- 注意：不能用 redis.call('time') 取时间（TIME 是非确定性命令，Redis 5.0 之前不允许其后再写命令）
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('pexpire', KEYS[1], ARGV[2])
else
    return 0
end
