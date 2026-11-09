package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Redis实现全局唯一id
 * 时间戳+序列号
 */
@Component
public class RedisIdWorker {
    public static final long BEGIN_TIMESTAMP=1735689600L;
    private static final int COUNT_BITS=32;
    /** 序列号按「业务前缀 + 日期」分别自增，避免不同业务互相挤占同一个序列 */
    private static final String ID_KEY_PREFIX = "icr:";
    private static final long MAX_SEQUENCE = (1L << COUNT_BITS) - 1;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }
    public long nextId(String keyPrefix){
//      时间戳取真实 epoch 秒（Instant 与时区无关，不受 JVM 默认时区影响）
        long nowSecond = Instant.now().getEpochSecond();
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
//      日期分桶按业务时区划分，保证 key 的天边界与业务日一致
        String date = SystemConstants.bizNow().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        String key = ID_KEY_PREFIX + keyPrefix + ":" + date;
        long count =stringRedisTemplate.opsForValue().increment(key,1);
        // key 只在当天有效，保留两天后自动清理，避免 Redis 无限增长
        if (count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofDays(2));
        }
        if (count > MAX_SEQUENCE) {
            throw new IllegalStateException("业务 " + keyPrefix + " 当日序列号已用尽：" + count);
        }
        return timeStamp << COUNT_BITS | count;

    }

}
