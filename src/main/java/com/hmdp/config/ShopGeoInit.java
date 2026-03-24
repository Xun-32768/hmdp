package com.hmdp.config;

import com.hmdp.pojo.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ShopGeoInit implements CommandLineRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("开始预热店铺地理位置数据到 Redis...");

        // 1. 查询所有店铺信息
        List<Shop> list = shopService.list();

        // 2. 按照 typeId 分组，同一个类型的店铺存入同一个 GEO Key 中
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 分批写入 Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型ID
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;

            // 获取该类型下的店铺集合
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());

            for (Shop shop : shops) {
                // 将经纬度和店铺ID封装到 GeoLocation 中
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }

            // 批量写入，减少网络 IO 耗时
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
        log.info("店铺地理位置数据预热完成！");
    }
}