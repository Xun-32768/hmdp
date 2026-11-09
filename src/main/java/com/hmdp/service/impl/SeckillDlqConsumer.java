package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.entity.VoucherOrder;
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
 * <p>进入死信队列的消息代表「Redis 已预扣但落库失败」，属于必须人工介入的异常。
 * 这里不再重试，只做三件事：告警日志、写 Redis 审计列表、ACK 避免死信堆积。
 *
 * <p>审计记录是人工补单 / 退款的唯一依据，因此写入失败时宁可让消息重回死信队列，
 * 也不允许消息与审计同时消失。
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Component
public class SeckillDlqConsumer {

    /** 审计列表保留条数，超出后从尾部裁剪旧记录 */
    private static final long MAX_RECORD = 1000L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 死信消费入口：记录审计后 ACK。
     *
     * <p>审计写入失败时不 ACK，让消息重回死信队列：
     * 死信堆积至少还有告警，而丢消息会导致用户已扣库存却永远拿不到订单。
     *
     * @param voucherOrder 订单消息体
     * @param message      原始消息，用于读取失败原因与 {@code x-death} 追踪头
     * @param channel      AMQP 信道
     * @param deliveryTag  投递标签
     * @throws IOException ACK / NACK 失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_DLQ)
    public void listenDlq(@Payload VoucherOrder voucherOrder,
                          Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Object reason = message.getMessageProperties().getHeader(RabbitMQConfig.HEADER_FAIL_REASON);
        Object death = message.getMessageProperties().getHeader("x-death");
        log.error("【秒杀死信告警】订单落库最终失败，需人工处理：orderId={}, voucherId={}, userId={}, reason={}, x-death={}",
                voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), reason, death);
        try {
            record(voucherOrder, reason);
            // 已持久化审计，ACK 掉死信，避免无意义堆积
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 审计写入失败时让消息留在死信队列，宁可堆积也不能丢
            log.error("秒杀死信审计写入失败，消息将保留在死信队列，orderId={}", voucherOrder.getId(), e);
            channel.basicNack(deliveryTag, false, true);
        }
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