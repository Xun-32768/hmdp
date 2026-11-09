-- 秒杀失败补偿脚本：回滚库存与用户下单标记（幂等）
-- KEYS[1] = 库存 key        seckill:stock:{voucherId}
-- KEYS[2] = 已下单用户集合   seckill:order:{voucherId}
-- ARGV[1] = 用户id
-- ARGV[2] = 库存是否已预热（"1" 需要回补，"0" 不回补，避免凭空造出库存）
--
-- 幂等保证：只有 SREM 确实移除了该用户（返回 1）时才回补库存，
-- 因此重复回滚（如 confirm 与 return 双回调、重试等）不会凭空增加库存。

local userId = ARGV[1]
local hasStock = ARGV[2]
local stockKey = KEYS[1]
local orderKey = KEYS[2]

local removed = redis.call('srem', orderKey, userId)
if (removed == 1 and hasStock == '1' and redis.call('exists', stockKey) == 1) then
    redis.call('incrby', stockKey, 1)
end

return removed