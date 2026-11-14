package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 秒杀死信消费者。
 *
 * <p>统一消费 {@link RabbitMQConfig#SECKILL_DLQ} 队列，承担两类死信处理：
 * <ol>
 *   <li><b>15分钟超时延迟死信</b>：由 {@link RabbitMQConfig#SECKILL_TIMEOUT_DELAY_QUEUE} 到期转发，
 *       检查订单是否仍处于「未支付」状态，若未支付则执行 CAS 关单并归还 DB/Redis 库存与一人一单资格。</li>
 *   <li><b>入库失败死信</b>：处理异常、重试超限或库存不一致的消息，调用 {@link #handleFailedOrder}
 *       归还预扣资源，避免死锁或阻断用户后续下单。</li>
 * </ol>
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Component
public class SeckillDlqConsumer {

    /** 订单状态：未支付 */
    private static final byte ORDER_STATUS_UNPAID = 1;

    /** 审计列表保留条数，超出后从尾部裁剪旧记录 */
    private static final long MAX_RECORD = 1000L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private SeckillOrderCancelService cancelService;

    /**
     * 死信消费入口：统一处理超时关单与落库失败死信。
     *
     * @param voucherOrder 订单消息体
     * @param message      原始消息
     * @param channel      AMQP 信道
     * @param deliveryTag  投递标签
     * @throws IOException ACK / NACK 失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_DLQ)
    public void listenDlq(@Payload VoucherOrder voucherOrder,
                          Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        if (voucherOrder == null || voucherOrder.getId() == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        Object reason = message.getMessageProperties().getHeader(RabbitMQConfig.HEADER_FAIL_REASON);
        Object death = message.getMessageProperties().getHeader("x-death");

        try {
            if (reason != null) {
                // 1. 带有失败原因的消息：属于落库失败/重试超限死信
                log.error("【秒杀落库失败死信】执行异常资源释放与审计：orderId={}, voucherId={}, userId={}, reason={}, x-death={}",
                        voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), reason, death);
                record(voucherOrder, reason);
                handleFailedOrder(voucherOrder);
            } else {
                // 2. 无失败原因的消息：属于 15 分钟超时延迟队列到期的死信消息，执行未支付检查与超时关单
                handleTimeoutOrder(voucherOrder);
            }

            // 处理完成后确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("秒杀死信消费处理异常，保留消息重试，orderId={}", voucherOrder.getId(), e);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 处理 15 分钟超时订单：先查是否未支付，若是则执行 CAS 关单并回滚库存与资格
     */
    private void handleTimeoutOrder(VoucherOrder voucherOrder) {
        Long orderId = voucherOrder.getId();
        VoucherOrder currentOrder = voucherOrderService.getById(orderId);
        if (currentOrder == null) {
            log.warn("超时关单检测：订单记录不存在，orderId={}", orderId);
            return;
        }

        // 检查订单状态：只有未支付（status == 1）的订单才执行取消与回滚
        if (currentOrder.getStatus() == ORDER_STATUS_UNPAID) {
            boolean canceled = cancelService.cancelOrder(currentOrder);
            if (canceled) {
                log.info("【死信超时关单成功】未支付订单已自动取消并归还库存与资格：orderId={}, voucherId={}, userId={}",
                        orderId, currentOrder.getVoucherId(), currentOrder.getUserId());
            } else {
                log.info("【死信超时关单跳过】订单状态已变更（可能刚完成支付或已取消）：orderId={}", orderId);
            }
        } else {
            log.info("【死信超时关单跳过】订单非未支付状态，当前状态 status={}，orderId={}",
                    currentOrder.getStatus(), orderId);
        }
    }

    /**
     * 处理落库失败的死信订单：回滚 Redis 预扣库存并释放用户一人一单下单资格
     */
    private void handleFailedOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        if (voucherId == null || userId == null) {
            return;
        }
        // 归还 Redis 预扣库存
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().increment(stockKey);
        }
        // 释放 Redis 一人一单限制，防止用户被永久卡住无法再抢
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + voucherId, userId.toString());
        log.warn("【落库失败死信资源已归还】已回滚 Redis 库存并移除一人一单限制：voucherId={}, userId={}", voucherId, userId);
    }

    /**
     * 把失败订单写入 Redis 审计列表（保留最近 {@link #MAX_RECORD} 条）。
     *
     * <p>JSON 序列化失败时退化为 {@code Map.toString()}，保证审计记录不丢。
     *
     * @param order  失败订单
     * @param reason 进入死信的原因，可能为 {@code null}
     */
    private void record(VoucherOrder order, Object reason) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("orderId", order.getId());
        record.put("voucherId", order.getVoucherId());
        record.put("userId", order.getUserId());
        record.put("reason", reason == null ? "unknown" : reason.toString());
        record.put("time", System.currentTimeMillis());
        String json;
        try {
            json = objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            // 序列化失败时退化为 Map.toString()，保证审计记录不丢
            log.warn("死信审计记录序列化失败，退化为字符串记录，orderId={}", order.getId(), e);
            json = record.toString();
        }
        String listKey = RabbitMQConfig.SECKILL_DLQ_RECORD_KEY;
        stringRedisTemplate.opsForList().leftPush(listKey, json);
        stringRedisTemplate.opsForList().trim(listKey, 0, MAX_RECORD - 1);
    }
}