package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedissonClient redissonClient;

    public Result queryById(Long id){
//        布隆过滤器过滤无效请求
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        // 判断 ID 是否存在
        if (!bloomFilter.contains(id)) {
            log.info("检测到缓存穿透攻击，拦截非法请求 ID: {}",id);
            return null;
        }
//        缓存空值
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//         逻辑过期解决缓存击穿
//         Shop shop = cacheClient
//                 .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


//    这一部分要了解清楚  返回的数据结构GeoResults
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y){

        if(x==null || y==null){
            Page<Shop> page=
                    query().eq("type_id",typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
//       1.获取分页参数
        int from =(current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end= current * SystemConstants.DEFAULT_PAGE_SIZE;
//      2.查询redis，根据距离排序、分页
        String key="shop:geo:"+ typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo().search(
                key, GeoReference.fromCoordinate(x,y),new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
//      3.获取id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = Optional.ofNullable(geoResults)
                .map(GeoResults::getContent)
                .orElse(Collections.emptyList());

        if(list.isEmpty() || list.size()<=from) {
            return Result.ok(Collections.emptyList());
        }

//      4.截取from - end的部分
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap =new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            String shopIdStr= result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
//            到中心点的距离
            Distance distance=result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

//      5.根据id查询shop
        String idStr= StrUtil.join(",",ids);
//      注意排序
        List<Shop> shops=query().in("id",ids).last("order by field(id,"+ idStr +")").list();
        for(Shop shop:shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


    public Result saveShop(Shop shop){
        // 写入数据库
        save(shop);
//        插入布隆过滤器
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        bloomFilter.add(shop.getId());
//        写入Redis
        Long typeId=shop.getTypeId();
        String key="shop:geo:"+ typeId;
        RedisGeoCommands.GeoLocation<String>  location = new RedisGeoCommands.GeoLocation<>(
             shop.getId().toString(),new Point(shop.getX(),shop.getY()));
        stringRedisTemplate.opsForGeo().add(key, location);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

}
