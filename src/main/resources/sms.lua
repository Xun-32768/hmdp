-- KEYS[1]: 限流的 key，例如 "sms:limit:13800138000"
local key = KEYS[1]

-- ARGV[1]: 当前时间戳 (毫秒)
local now = tonumber(ARGV[1])
-- ARGV[2]: 窗口的起始时间戳 (当前时间 - 窗口大小，毫秒)
local windowStart = tonumber(ARGV[2])
-- ARGV[3]: 窗口内的最大允许请求次数 (限制阀值)
local limit = tonumber(ARGV[3])
-- ARGV[4]: 唯一 Member 标识 (UUID，防止并发时 Score 相同导致 Member 被覆盖)
local member = ARGV[4]
-- ARGV[5]: 窗口大小 (秒，用于设置 Key 的过期时间)
local windowExpire = tonumber(ARGV[5])

-- 1. 清除时间窗口之外的旧数据 (0 到 windowStart)
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

-- 2. 获取当前窗口内剩下的请求总数
local currentCount = tonumber(redis.call('ZCARD', key))

-- 3. 判断是否超过限制
if currentCount >= limit then
    -- 超过限制，拒绝放行
    return 0
end

-- 4. 未超过限制，允许放行，将当前请求记录放入 ZSet
redis.call('ZADD', key, now, member)

-- 5. 重置整个 ZSet 的过期时间 (兜底机制，防止冷数据永久占用内存)
redis.call('EXPIRE', key, windowExpire)

-- 6. 返回成功
return 1