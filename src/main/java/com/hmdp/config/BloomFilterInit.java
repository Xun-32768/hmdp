package com.hmdp.config;

import com.hmdp.pojo.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.List;

//布隆过滤器，在项目启动时将所有shop的Id存放在过滤器中国
@Component
@Slf4j
public class BloomFilterInit implements CommandLineRunner {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private IShopService shopService;

    @Override
    public void run(String... args) {
        log.info("开始预热布隆过滤器.....");

        // 1. 获取布隆过滤器实例
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");

        // 2. 初始化参数：预期插入量，误判率（通常设为 0.03 或 0.01）
        // 误判率越低，占用的 Bit 位越多
        bloomFilter.tryInit(100000L, 0.03);

        // 3. 预热数据：从数据库查出所有商铺 ID
        List<Long> ids = shopService.list().stream().map(Shop::getId).toList();

        // 4. 批量写入过滤器
        ids.forEach(bloomFilter::add);
        log.info("布隆过滤器预热结束....");
    }
}