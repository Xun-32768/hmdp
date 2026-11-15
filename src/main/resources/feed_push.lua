-- 把同一篇笔记分发到多个用户收件箱（推模式写扩散）
--
-- KEYS[1..n] 目标收件箱 ZSet key（feed:{userId}）
-- ARGV[1]    member，即笔记 ID
-- ARGV[2]    score，即发布时间戳（毫秒）
-- ARGV[3]    单个收件箱的最大保留条数
-- ARGV[4]    TTL（秒），<= 0 表示不设置
-- ARGV[5]    "1" 表示只写入「已存在」的收件箱（只推活跃用户），"0" 表示不存在也创建
--
-- 返回：实际写入的收件箱数量
--
-- 说明：EXISTS / ZCARD / ZREMRANGEBYRANK 必须放在脚本里做，
--       放进 Java 侧 Pipeline 回调里拿不到即时返回值（命令只是入队），必然误判。
local member = ARGV[1]
local score = ARGV[2]
local maxLen = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])
local skipIfAbsent = ARGV[5] == '1'

local pushed = 0
for i = 1, #KEYS do
    local key = KEYS[i]
    if (not skipIfAbsent) or redis.call('EXISTS', key) == 1 then
        redis.call('ZADD', key, score, member)
        if maxLen and maxLen > 0 then
            local size = redis.call('ZCARD', key)
            -- 必须先用 ZCARD 判长度再算裁剪区间：
            -- 直接 ZREMRANGEBYRANK key 0 -(maxLen+1) 在集合不足 maxLen 时会把元素全部删掉
            if size > maxLen then
                redis.call('ZREMRANGEBYRANK', key, 0, size - maxLen - 1)
            end
        end
        if ttl and ttl > 0 then
            redis.call('EXPIRE', key, ttl)
        end
        pushed = pushed + 1
    end
end
return pushed