package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//当前类初始化完毕后执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list==null || list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    redisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list==null || list.isEmpty()){
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    redisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }
        }
    }

    /*  有了redis的消息队列就不需要阻塞队列了
    private BlockingDeque<VoucherOrder> orderTasks = new LinkedBlockingDeque<>(1024*1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }

            }
        }
    }*/

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("不允许重复下单");
            return;
        }
        try{
            proxy.createAoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = redisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
        int r=result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        proxy= (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    //旧时代
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long result = redisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString());
        int r=result.intValue();
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        proxy= (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }*/


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();

        //不使用分布锁
*//*        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createAoucherOrder(voucherId);
        }*//*
        //分布式锁
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createAoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }*/

    @Transactional
    public void createAoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getId();

        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

}
