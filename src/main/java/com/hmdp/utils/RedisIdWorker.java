package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Redis实现全局唯一id
 * 时间戳+序列号
 */
@Component
public class RedisIdWorker {
    public static final long BEGIN_TIMESTAMP=1735689600L;
    private static final int COUNT_BITS=32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }
    public long nextId(String kryPrefix){
//      生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_TIMESTAMP;
//      生成序列号,同一天的序列号唯一且自增
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count =stringRedisTemplate.opsForValue().increment(date,1);
        return timeStamp << COUNT_BITS | count;

    }

}
