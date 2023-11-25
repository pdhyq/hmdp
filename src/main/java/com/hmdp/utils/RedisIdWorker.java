package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate redisTemplate;
    private static final long BEGIN_TIMESTAMP = 1672531200;
    private static final int COUNT_BITS = 32;
    public long nextId(String keyPrefix){
        //时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        return timestamp<<COUNT_BITS|count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0, 0);
        System.out.println(time.toEpochSecond(ZoneOffset.UTC));
    }
}
