package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jdk.jfr.Label;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    //    读取lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //  异步处理线程池
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //  在jvm中创建阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 设置代理
//    private IVoucherOrderService proxy;
    @Resource
    @Lazy
    private IVoucherOrderService proxy;



    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
//       执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

//        使用RabbitMQ
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_EXCHANGE,
                RabbitMQConfig.SECKILL_ROUTING_KEY,
                voucherOrder
        );

        // 2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);
        //3.获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //4.返回订单id

        return Result.ok(orderId);

        /*//1.查询优惠券
        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀还未开始");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足!");
        }
        Long userId=UserHolder.getUser().getId();
        RLock lock=redissonClient.getLock("lock:order:"+userId);
        boolean isLock=lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            //设置代理调用createVoucherOrder。避免内部调用导致事务不生效
            IVoucherOrderService proxy=(IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }*/

    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count());
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足");
            return;
        }
        save(voucherOrder);
    }

    //    RabbitMQ消息队列实现
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void listenSeckillOrder(@Payload VoucherOrder voucherOrder){
        log.info("接收到秒杀订单消息，准备入库：订单号={}", voucherOrder.getId());
        try{
            // 注意：因为这里是异步线程，没有 HTTP 上下文，无法通过 UserHolder 获取 UserId
            handleVoucherOrder(voucherOrder);

        }catch(Exception e){
            log.error("处理秒杀订单失败", e);
            // 如果抛出异常，Spring AMQP 默认会自动重试（如果你在 yml 里配了 retry）
            // 超过重试次数会打印异常或进入死信队列
            throw e;
        }
    }
//TODO RabbitMQ实现手动ACK和死信队列，以及数据库消费失败的Redis回滚机制
/* //Redis Stream 消息队列的实现
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            stringRedisTemplate.opsForStream().createGroup("stream.orders", ReadOffset.from("0"), "g1");
        } catch (Exception e) {
            log.info("消费者组 g1 已存在或创建失败 (正常现象)");
        }
        proxy = applicationContext.getBean(IVoucherOrderService.class);
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        private final String queueName = "stream.orders";
        private final String groupName = "g1";
        private final String consumerName = "c1";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 3. 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 4. 执行下单逻辑 (如果这里报错，会抛出异常，跳过 ACK，进入 catch)
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK 确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 出现异常，消息没确认，进入 pending-list，需要处理
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取 pending-list 中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1), // 这里不需要 block，因为是读已存在的异常消息
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果为 null或者为空，说明 pending-list 里没有异常消息了，结束循环，回到主循环
                        break;
                    }

                    // 3. 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 4. 执行下单逻辑
                    handleVoucherOrder(voucherOrder);

                    // 5. ACK 确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());

                } catch (Exception e) {
                    log.error("处理 pending-list 订单异常", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) { // 注意这里改名叫 ex
                        ex.printStackTrace();
                    }
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //1.获取用户
            Long userId = voucherOrder.getUserId();
            // 2.创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 3.尝试获取锁
            boolean isLock = redisLock.tryLock();
            // 4.判断是否获得锁成功
            if (!isLock) {
                // 获取锁失败，直接返回失败或者重试
                log.error("不允许重复下单！");
                return;
            }
            try {
                //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁
                redisLock.unlock();
            }
        }

    }
    @PreDestroy // 容器销毁前执行
    public void destroy() {
        SECKILL_ORDER_EXECUTOR.shutdown();
        log.info("秒杀线程池已关闭");
    }*/



}
