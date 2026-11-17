package com.hmdp.listener;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.config.ShopEsInit;
import com.hmdp.pojo.dto.ShopDoc;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.pojo.entity.ShopType;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 监听商户数据变更消息，实时同步 Elasticsearch
 */
@Component
@Slf4j
public class ShopEsListener {

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @RabbitListener(queues = RabbitMQConfig.SHOP_INSERT_QUEUE)
    public void listenShopInsertOrUpdate(Long id, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("接收到商户同步消息，ID: {}", id);
            // 查库
            Shop shop = shopService.getById(id);
            if (shop == null) {
                // 如果查询不到，说明被删除了，执行删除操作
                esClient.delete(d -> d.index(ShopEsInit.SHOP_INDEX).id(id.toString()));
            } else {
                String typeName = null;
                if (shop.getTypeId() != null) {
                    ShopType shopType = shopTypeService.getById(shop.getTypeId());
                    if (shopType != null) {
                        typeName = shopType.getName();
                    }
                }
                ShopDoc doc = new ShopDoc(shop, typeName);
                esClient.index(i -> i.index(ShopEsInit.SHOP_INDEX).id(id.toString()).document(doc));
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理商户 ES 增量同步异常，ID: {}", id, e);
            // 异常不回投死循环，直接 ack 或做死信
            channel.basicAck(deliveryTag, false);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.SHOP_DELETE_QUEUE)
    public void listenShopDelete(Long id, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            log.info("接收到商户删除消息，ID: {}", id);
            esClient.delete(d -> d.index(ShopEsInit.SHOP_INDEX).id(id.toString()));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理商户 ES 删除异常，ID: {}", id, e);
            channel.basicAck(deliveryTag, false);
        }
    }
}
