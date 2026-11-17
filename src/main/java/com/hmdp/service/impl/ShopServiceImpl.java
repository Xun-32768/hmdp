package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.GeoDistanceSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.json.JsonData;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.config.ShopEsInit;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ShopDoc;
import com.hmdp.pojo.dto.ShopSearchDTO;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    public Result queryById(Long id){
//        布隆过滤器过滤无效请求
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        // 判断 ID 是否存在
        if (!bloomFilter.contains(id)) {
            log.info("检测到缓存穿透攻击，拦截非法请求 ID: {}",id);
            return Result.fail("店铺不存在");
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


    @Transactional
    public Result saveShop(Shop shop){
        // 1. 写入数据库
        save(shop);
        Long shopId = shop.getId();

        // 2. 插入布隆过滤器，防止缓存穿透
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");
        bloomFilter.add(shopId);

        // 3. 写入 Redis GEO（如果经纬度非空）
        if (shop.getX() != null && shop.getY() != null && shop.getTypeId() != null) {
            String key = "shop:geo:" + shop.getTypeId();
            RedisGeoCommands.GeoLocation<String> location = new RedisGeoCommands.GeoLocation<>(
                    shopId.toString(), new Point(shop.getX(), shop.getY()));
            stringRedisTemplate.opsForGeo().add(key, location);
        }

        // 4. 发送 RabbitMQ 消息异步同步到 Elasticsearch
        rabbitTemplate.convertAndSend(RabbitMQConfig.SHOP_EXCHANGE, RabbitMQConfig.SHOP_INSERT_KEY, shopId);

        // 返回店铺id
        return Result.ok(shopId);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);

        // 2. 删除 Redis 详情缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        // 3. 发送 RabbitMQ 消息同步更新 Elasticsearch
        rabbitTemplate.convertAndSend(RabbitMQConfig.SHOP_EXCHANGE, RabbitMQConfig.SHOP_INSERT_KEY, shop.getId());

        return Result.ok();
    }

    @Override
    public Result searchShops(ShopSearchDTO params) {
        try {
            // 1. 构建 Bool 查询
            BoolQuery.Builder boolQuery = new BoolQuery.Builder();

            // 1.1 关键字全文搜索（商铺名称、类型名称、地址）
            if (StrUtil.isNotBlank(params.getName())) {
                boolQuery.must(m -> m
                        .multiMatch(mm -> mm
                                .fields("name^2", "typeName^1.5", "address")
                                .query(params.getName())
                        )
                );
            } else {
                boolQuery.must(m -> m.matchAll(ma -> ma));
            }

            // 1.2 分类过滤
            if (params.getTypeId() != null) {
                boolQuery.filter(f -> f.term(t -> t.field("typeId").value(params.getTypeId())));
            }

            // 1.3 商圈过滤
            if (StrUtil.isNotBlank(params.getArea())) {
                boolQuery.filter(f -> f.term(t -> t.field("area").value(params.getArea())));
            }

            // 1.4 价格区间过滤
            if (params.getMinPrice() != null || params.getMaxPrice() != null) {
                boolQuery.filter(f -> f.range(r -> r.number(n -> {
                    n.field("avgPrice");
                    if (params.getMinPrice() != null) {
                        n.gte((double) params.getMinPrice());
                    }
                    if (params.getMaxPrice() != null) {
                        n.lte((double) params.getMaxPrice());
                    }
                    return n;
                })));
            }

            // 1.5 评分过滤
            if (params.getMinScore() != null) {
                boolQuery.filter(f -> f.range(r -> r.number(n -> n
                        .field("score")
                        .gte((double) params.getMinScore())
                )));
            }

            // 1.6 地理位置距离过滤
            boolean hasGeo = params.getX() != null && params.getY() != null;
            if (hasGeo && params.getDistance() != null && params.getDistance() > 0) {
                boolQuery.filter(f -> f.geoDistance(gd -> gd
                        .field("location")
                        .distance(params.getDistance() + "m")
                        .location(loc -> loc.latlon(ll -> ll.lat(params.getY()).lon(params.getX())))
                ));
            }

            // 2. 构建 SearchRequest
            int current = (params.getCurrent() != null && params.getCurrent() > 0) ? params.getCurrent() : 1;
            int pageSize = (params.getPageSize() != null && params.getPageSize() > 0) ? params.getPageSize() : 10;
            int from = (current - 1) * pageSize;

            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index(ShopEsInit.SHOP_INDEX)
                    .query(Query.of(q -> q.bool(boolQuery.build())))
                    .from(from)
                    .size(pageSize);

            // 3. 排序策略
            String sortBy = StrUtil.blankToDefault(params.getSortBy(), "default");
            List<SortOptions> sortList = new ArrayList<>();
            switch (sortBy) {
                case "distance":
                    if (hasGeo) {
                        sortList.add(new SortOptions.Builder()
                                .geoDistance(GeoDistanceSort.of(g -> g
                                        .field("location")
                                        .location(loc -> loc.latlon(ll -> ll.lat(params.getY()).lon(params.getX())))
                                        .order(SortOrder.Asc)
                                        .unit(co.elastic.clients.elasticsearch._types.DistanceUnit.Meters)
                                ))
                                .build());
                    }
                    break;
                case "sold":
                    sortList.add(new SortOptions.Builder().field(f -> f.field("sold").order(SortOrder.Desc)).build());
                    break;
                case "score":
                    sortList.add(new SortOptions.Builder().field(f -> f.field("score").order(SortOrder.Desc)).build());
                    break;
                case "price_asc":
                    sortList.add(new SortOptions.Builder().field(f -> f.field("avgPrice").order(SortOrder.Asc)).build());
                    break;
                case "price_desc":
                    sortList.add(new SortOptions.Builder().field(f -> f.field("avgPrice").order(SortOrder.Desc)).build());
                    break;
                default:
                    // 默认相关度排序 _score
                    sortList.add(new SortOptions.Builder().score(s -> s.order(SortOrder.Desc)).build());
                    break;
            }
            searchBuilder.sort(sortList);

            // 4. 高亮设置
            if (StrUtil.isNotBlank(params.getName())) {
                searchBuilder.highlight(h -> h
                        .fields("name", HighlightField.of(hf -> hf.preTags("<font color='red'>").postTags("</font>")))
                );
            }

            // 5. 执行搜索
            SearchResponse<ShopDoc> response = esClient.search(searchBuilder.build(), ShopDoc.class);

            // 6. 解析结果
            List<Hit<ShopDoc>> hits = response.hits().hits();
            List<ShopDoc> records = new ArrayList<>(hits.size());
            for (Hit<ShopDoc> hit : hits) {
                ShopDoc doc = hit.source();
                if (doc == null) continue;

                // 6.1 提取高亮替换商户名称
                if (hit.highlight() != null && hit.highlight().containsKey("name")) {
                    List<String> hlNames = hit.highlight().get("name");
                    if (hlNames != null && !hlNames.isEmpty()) {
                        doc.setName(hlNames.get(0));
                    }
                }

                // 6.2 提取距离排序信息
                if (hit.sort() != null && !hit.sort().isEmpty() && "distance".equals(sortBy)) {
                    try {
                        co.elastic.clients.elasticsearch._types.FieldValue fv = hit.sort().get(0);
                        double distVal = fv.isDouble() ? fv.doubleValue() : Double.parseDouble(fv._get().toString());
                        doc.setDistance(Math.round(distVal * 100.0) / 100.0);
                    } catch (Exception ignore) {}
                } else if (hasGeo && doc.getX() != null && doc.getY() != null) {
                    // 非距离排序但传递了坐标时，自动计算与用户的实际距离
                    double dist = calculateDistance(params.getY(), params.getX(), doc.getY(), doc.getX());
                    doc.setDistance(Math.round(dist * 100.0) / 100.0);
                }

                records.add(doc);
            }

            long total = response.hits().total() != null ? response.hits().total().value() : 0L;
            return Result.ok(records, total);
        } catch (Exception e) {
            log.error("Elasticsearch 复合搜索异常", e);
            return Result.fail("商户搜索失败");
        }
    }

    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double a = radLat1 - radLat2;
        double b = Math.toRadians(lon1) - Math.toRadians(lon2);
        double s = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(a / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.pow(Math.sin(b / 2), 2)));
        return s * 6378137.0;
    }

    @Override
    public Result getShopSuggestions(String prefix) {
        if (StrUtil.isBlank(prefix)) {
            return Result.ok(Collections.emptyList());
        }
        try {
            SearchResponse<ShopDoc> response = esClient.search(s -> s
                    .index(ShopEsInit.SHOP_INDEX)
                    .suggest(sg -> sg
                            .suggesters("shop_suggest", sug -> sug
                                    .prefix(prefix)
                                    .completion(c -> c.field("suggestion").skipDuplicates(true).size(10))
                            )
                    ), ShopDoc.class);

            List<String> list = new ArrayList<>();
            List<Suggestion<ShopDoc>> suggestions = response.suggest().get("shop_suggest");
            if (suggestions != null) {
                for (Suggestion<ShopDoc> suggestion : suggestions) {
                    suggestion.completion().options().forEach(opt -> list.add(opt.text()));
                }
            }

            // 若 completion 前缀匹配不足，使用 match_phrase_prefix 兜底匹配店名与类型名
            if (list.size() < 5) {
                SearchResponse<ShopDoc> phraseRes = esClient.search(s -> s
                        .index(ShopEsInit.SHOP_INDEX)
                        .query(q -> q.bool(b -> b
                                .should(s1 -> s1.matchPhrasePrefix(mpp -> mpp.field("name").query(prefix)))
                                .should(s2 -> s2.matchPhrasePrefix(mpp -> mpp.field("typeName").query(prefix)))
                        ))
                        .source(src -> src.filter(f -> f.includes("name", "typeName")))
                        .size(10 - list.size()), ShopDoc.class);
                if (phraseRes.hits() != null && phraseRes.hits().hits() != null) {
                    for (Hit<ShopDoc> h : phraseRes.hits().hits()) {
                        if (h.source() != null) {
                            if (StrUtil.isNotBlank(h.source().getTypeName()) && !list.contains(h.source().getTypeName())) {
                                list.add(h.source().getTypeName());
                            }
                            if (StrUtil.isNotBlank(h.source().getName()) && !list.contains(h.source().getName())) {
                                list.add(h.source().getName());
                            }
                        }
                    }
                }
            }

            return Result.ok(list);
        } catch (Exception e) {
            log.error("Elasticsearch 建议词查询异常", e);
            return Result.fail("获取建议词失败");
        }
    }

}
