package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),time,unit);
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key= keyPrefix+id;
        String json = redisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if(json!=null){
            return null;
        }

        R r= dbFallback.apply(id);
        if(r==null){
            redisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //逻辑删除
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key= keyPrefix+id;
        String json = redisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        String localKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localKey);
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(localKey);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        redisTemplate.delete(key);
    }
}
