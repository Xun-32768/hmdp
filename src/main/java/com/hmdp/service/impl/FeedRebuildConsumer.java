package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.service.IBlogService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Feed 收件箱重建消费者。
 *
 * <p>触发时机：用户收件箱（{@code feed:{userId}}）因 7 天 TTL 过期，
 * 刷流时检测到 key 不存在即投递一条重建消息。
 *
 * <p>重建内容是关注列表中<b>小博主</b>近 7 天的笔记（最多 100 条），
 * 大V 的内容由刷流时实时拉取发件箱，不落收件箱。
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Component
public class FeedRebuildConsumer {

    @Resource
    private IBlogService blogService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 消费重建事件。
     *
     * <p>失败处理遵循「延迟重试 → 死信留档」：重建是幂等操作，
     * 但直接 {@code basicNack(requeue=true)} 会立刻空转重投，
     * 因此先转入 TTL 延迟重试队列，重试超限后转死信。
     *
     * @param userId      需要重建收件箱的用户 ID
     * @param message     原始消息
     * @param channel     AMQP 信道
     * @param deliveryTag 投递标签
     * @param retryCount  已重试次数（来自消息头，首次消费为 {@code null}）
     * @throws IOException ACK / NACK 失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.FEED_REBUILD_QUEUE)
    public void handleRebuild(@Payload Long userId,
                              Message message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              @Header(value = RabbitMQConfig.FEED_HEADER_RETRY_COUNT, required = false) Integer retryCount)
            throws IOException {
        if (userId == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            blogService.rebuildFeed(userId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retried = retryCount == null ? 0 : retryCount;
            log.error("Feed 收件箱重建失败，userId={}, 已重试={}", userId, retried, e);
            if (retried < RabbitMQConfig.FEED_MAX_RETRY) {
                if (publishToRetryQueue(userId, retried + 1)) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    channel.basicNack(deliveryTag, false, true);
                }
            } else if (publishToDlq(userId, "重建重试次数超限")) {
                channel.basicAck(deliveryTag, false);
            } else {
                log.error("重建死信投递失败，保留未确认消息等待重投，userId={}", userId);
            }
        }
    }

    /**
     * 投递到重建延迟重试队列。
     *
     * @param userId         待重建的用户 ID
     * @param nextRetryCount 本次重试序号
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToRetryQueue(Long userId, int nextRetryCount) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.FEED_REBUILD_RETRY_EXCHANGE,
                    RabbitMQConfig.FEED_REBUILD_RETRY_ROUTING_KEY,
                    userId,
                    message -> {
                        message.getMessageProperties()
                                .setHeader(RabbitMQConfig.FEED_HEADER_RETRY_COUNT, nextRetryCount);
                        return message;
                    }
            );
            log.warn("Feed 重建转入延迟重试，userId={}, 第 {} 次重试", userId, nextRetryCount);
            return true;
        } catch (Exception e) {
            log.error("Feed 重建投递重试队列失败，userId={}", userId, e);
            return false;
        }
    }

    /**
     * 投递到重建死信队列。
     *
     * @param userId 待重建的用户 ID
     * @param reason 失败原因
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToDlq(Long userId, String reason) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.FEED_REBUILD_DLX,
                    RabbitMQConfig.FEED_REBUILD_DLQ_ROUTING_KEY,
                    userId,
                    message -> {
                        message.getMessageProperties().setHeader("x-fail-reason", reason);
                        return message;
                    }
            );
            log.error("Feed 重建转入死信队列，userId={}, 原因={}", userId, reason);
            return true;
        } catch (Exception e) {
            log.error("Feed 重建死信投递失败，userId={}", userId, e);
            return false;
        }
    }
}