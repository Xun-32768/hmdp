-- 批量写入单个 ZSet（拉模式回填：关注/重建时把博主历史笔记塞进收件箱）
--
-- KEYS[1] 目标 ZSet key
-- ARGV[1] 最大保留条数
-- ARGV[2] TTL（秒），<= 0 表示不设置
-- ARGV[3..] 交替传入 member、score
--
-- 返回：写入后的 ZCARD
local maxLen = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

local i = 3
while i + 1 <= #ARGV do
    redis.call('ZADD', KEYS[1], ARGV[i + 1], ARGV[i])
    i = i + 2
end

local size = redis.call('ZCARD', KEYS[1])
if maxLen and maxLen > 0 and size > maxLen then
    redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - maxLen - 1)
end
if ttl and ttl > 0 then
    redis.call('EXPIRE', KEYS[1], ttl)
end
return size