-- 秒杀原子脚本
-- KEYS[1] = 库存 key        seckill:stock:{voucherId}
-- KEYS[2] = 已下单用户集合   seckill:order:{voucherId}
-- KEYS[3] = 活动时间窗口     seckill:time:{voucherId}  值格式 "{beginMillis}:{endMillis}"
-- ARGV[1] = 用户id
--（voucherId 已包含在 KEYS 里，订单id 由应用侧生成用于落库，脚本内均不需要）
-- 注意：当前时间取自 Redis 服务端（TIME 命令），不使用应用服务器时钟，
--       保证多节点部署时所有请求以同一时钟判定活动是否开始/结束。

local userId = ARGV[1]

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local timeKey = KEYS[3]

-- 取 Redis 服务端当前时间（UTC epoch 毫秒）。
local function redisNowMillis()
    if redis.replicate_commands then
        pcall(redis.replicate_commands)
    end
    local t = redis.call('TIME')                       -- 返回 {秒, 微秒}
    return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

-- 1.校验活动时间窗口（key 不存在时跳过，由预热任务保证存在）
local timeRange = redis.call('get', timeKey)
if (timeRange ~= nil and timeRange ~= false) then
    local sep = string.find(timeRange, ':')
    if sep then
        local beginTime = tonumber(string.sub(timeRange, 1, sep - 1))
        local endTime = tonumber(string.sub(timeRange, sep + 1))
        local now = redisNowMillis()
        if (beginTime ~= nil and now < beginTime) then
            return 3 -- 秒杀还未开始
        end
        if (endTime ~= nil and now > endTime) then
            return 4 -- 秒杀已经结束
        end
    end
end

-- 2.判断库存（key 不存在说明活动未预热或已清理，按活动不存在处理）
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil) then
    return 5 -- 秒杀活动不存在或已结束
end
if (stock <= 0) then
    return 1 -- 库存不足
end

-- 3.判断用户是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2 -- 不能重复下单
end

-- 4.扣减库存并记录下单用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

return 0

