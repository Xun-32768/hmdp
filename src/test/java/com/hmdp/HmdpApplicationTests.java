package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.pojo.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmdpApplicationTests {

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    //    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for(int i=0; i<100; i++) {
                long id=redisIdWorker.nextId("orderId");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i=0;i<300;i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time ="+(end-begin));
    }

//    加载shop数据到redis
//    @Test
    void loadGeo() throws InterruptedException {
//      1.获取所有店铺信息
        List<Shop> list= shopService.list();
//      2.根据typeId分组
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
//      3.分批写入Redis
        for(Map.Entry<Long,List<Shop>> entry:map.entrySet()){
            Long typeId=entry.getKey();
            String key="shop:geo:"+typeId;
            List<Shop> shops=entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(shops.size());
            for(Shop shop:shops){
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }

//    @Test
    void testCreateTokenForJMeter() throws IOException {
        // 1. 获取所有或指定数量的用户（确保数据库里已经有这些用户了）
        List<User> userList = userService.list(new QueryWrapper<User>().last("limit 1000"));

        // 2. 准备文件输出流
        PrintWriter writer = new PrintWriter(new FileWriter("D:\\hmdp_tokens.txt"));

        for (User user : userList) {
            // 3. 生成 Token (对应你代码里的 UUID)
            String token = UUID.randomUUID().toString(true);
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

            // 4. 将 User 转换为 UserDTO 并转为 Map (对应你代码里的字段转换逻辑)
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            // 5. 存入 Redis 并设置有效期
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            // 6. 将生成的 Token 写入文件，每行一个
            writer.println(token);
        }
        writer.close();
        System.out.println("成功为 " + userList.size() + " 个用户生成 Token 并存入 D:\\hmdp_tokens.txt");
    }
}
