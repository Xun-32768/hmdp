package com.hmdp.config;

import com.hmdp.pojo.dto.ShopDoc;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.pojo.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时全量同步店铺数据到 Elasticsearch 索引库
 */
@Component
@Slf4j
@Order(10)
public class ShopEsInit implements CommandLineRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private ElasticsearchClient esClient;

    public static final String SHOP_INDEX = "shop";

    private static final String INDEX_MAPPING_JSON = """
            {
              "mappings": {
                "properties": {
                  "id": { "type": "long" },
                  "name": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "typeId": { "type": "long" },
                  "typeName": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart", "fields": { "keyword": { "type": "keyword" } } },
                  "images": { "type": "keyword", "index": false },
                  "area": { "type": "keyword" },
                  "address": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "x": { "type": "double" },
                  "y": { "type": "double" },
                  "avgPrice": { "type": "long" },
                  "sold": { "type": "integer" },
                  "comments": { "type": "integer" },
                  "score": { "type": "integer" },
                  "openHours": { "type": "keyword", "index": false },
                  "location": { "type": "geo_point" },
                  "suggestion": { "type": "completion", "analyzer": "ik_max_word" }
                }
              }
            }
            """;

    @Override
    public void run(String... args) {
        log.info("开始检查并全量同步店铺数据到 Elasticsearch...");
        try {
            // 0. 检查索引是否存在及 mapping 是否合法
            boolean exists = esClient.indices().exists(e -> e.index(SHOP_INDEX)).value();
            if (exists) {
                GetMappingResponse getMappingRes = esClient.indices().getMapping(m -> m.index(SHOP_INDEX));
                Map<String, Property> properties = getMappingRes.get(SHOP_INDEX).mappings().properties();
                Property suggestionProp = properties.get("suggestion");
                Property locationProp = properties.get("location");
                Property typeNameProp = properties.get("typeName");
                if (suggestionProp == null || !suggestionProp.isCompletion() 
                        || locationProp == null || !locationProp.isGeoPoint() 
                        || typeNameProp == null) {
                    log.warn("检测到索引 {} 的 mapping 配置缺失或未包含 typeName 字段，正在重构索引...", SHOP_INDEX);
                    esClient.indices().delete(d -> d.index(SHOP_INDEX));
                    exists = false;
                }
            }

            if (!exists) {
                log.info("创建 Elasticsearch 索引 {} 并配置 IK 分词与补全 mapping...", SHOP_INDEX);
                esClient.indices().create(c -> c
                        .index(SHOP_INDEX)
                        .withJson(new StringReader(INDEX_MAPPING_JSON))
                );
                log.info("索引 {} 创建完成！", SHOP_INDEX);
            }

            // 1. 查询数据库中所有店铺数据
            List<Shop> shopList = shopService.list();
            if (shopList == null || shopList.isEmpty()) {
                log.info("数据库中无商户数据，跳过 ES 初始化同步");
                return;
            }

            // 1.1 查询所有分类构建 typeId -> typeName 映射
            Map<Long, String> typeMap = shopTypeService.list().stream()
                    .collect(Collectors.toMap(ShopType::getId, ShopType::getName, (k1, k2) -> k1));

            // 2. 构建 Bulk 批量同步请求
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (Shop shop : shopList) {
                String typeName = shop.getTypeId() != null ? typeMap.get(shop.getTypeId()) : null;
                ShopDoc doc = new ShopDoc(shop, typeName);
                br.operations(op -> op
                        .index(idx -> idx
                                .index(SHOP_INDEX)
                                .id(doc.getId().toString())
                                .document(doc)
                        )
                );
            }

            // 3. 执行 Bulk 请求
            BulkResponse response = esClient.bulk(br.build());
            if (response.errors()) {
                log.error("Elasticsearch 全量同步部分数据失败: {}", response.toString());
            } else {
                log.info("Elasticsearch 全量同步完成，共写入 {} 条店铺数据，耗时 {} ms",
                        shopList.size(), response.took());
            }
        } catch (Exception e) {
            log.error("Elasticsearch 全量数据同步异常", e);
        }
    }
}
