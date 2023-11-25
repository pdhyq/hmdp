package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl service;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSave(){
        service.saveShop2Redis(1L,10L);
    }
    @Test
    void testRedis(){
        redisTemplate.opsForValue().set("name","虎哥");
    }
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = ()->{
            for (int i=0;i<100;++i){
                long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }
            latch.countDown();
        };
        long begin=System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time= "+(end-begin));
    }


}
