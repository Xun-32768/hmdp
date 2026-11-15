package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 大V 晋升与状态迁移异步消费者。
 *
 * <p>触发时机：博主粉丝数突破 3500 晋升为大V 时，发送异步迁移事件。
 * 核心任务三部曲：
 * 1. 预热大V发件箱：查最近 100 篇动态写入 blog:outbox:{authorId}，消除历史内容空窗期；
 * 2. 销毁小V粉丝表：直接 DEL fans:{authorId}，防止形成 BigKey；
 * 3. 广播老粉丝集合：将该 authorId 加入其老粉丝的 famousFollows:{fanId} 集合。
 *
 * @author axun
 * @since 2026-11-15
 */
@Slf4j
@Component
public class FeedUpgradeConsumer {

    @Resource
    private IBlogService blogService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> FEED_BULK_SCRIPT;

    static {
        FEED_BULK_SCRIPT = new DefaultRedisScript<>();
        FEED_BULK_SCRIPT.setLocation(new ClassPathResource("feed_bulk.lua"));
        FEED_BULK_SCRIPT.setResultType(Long.class);
    }

    @RabbitListener(queues = RabbitMQConfig.FEED_UPGRADE_QUEUE)
    public void handleUpgrade(@Payload Long authorId,
                              Message message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        if (authorId == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            log.info("开始处理博主大V晋升异步迁移任务，authorId={}", authorId);

            // 1. 预热大V发件箱 blog:outbox:{authorId}，上限 100 篇，TTL 30 天
            warmUpOutbox(authorId);

            // 2. 销毁小V粉丝集合 fans:{authorId}
            stringRedisTemplate.delete(RedisConstants.FANS_KEY + authorId);

            // 3. 将该 authorId 批量加入所有老粉丝的 famousFollows 专属集合
            addAuthorToFollowersFamousSet(authorId);

            channel.basicAck(deliveryTag, false);
            log.info("博主大V晋升异步迁移完成，authorId={}", authorId);
        } catch (Exception e) {
            log.error("博主大V晋升异步迁移异常，authorId={}", authorId, e);
            // 业务异常不重投死循环，ACK 留日志
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 预热大V发件箱。
     */
    private void warmUpOutbox(Long authorId) {
        List<Blog> blogs = blogService.query()
                .eq("user_id", authorId)
                .orderByDesc("create_time")
                .last("LIMIT " + RedisConstants.BLOG_OUTBOX_MAX_LEN)
                .list();

        if (blogs == null || blogs.isEmpty()) {
            return;
        }

        String outboxKey = RedisConstants.BLOG_OUTBOX_KEY + authorId;
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(RedisConstants.BLOG_OUTBOX_MAX_LEN));
        args.add(String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.BLOG_OUTBOX_TTL_DAYS)));
        for (Blog b : blogs) {
            if (b.getId() != null && b.getCreateTime() != null) {
                long ts = b.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                args.add(b.getId().toString());
                args.add(String.valueOf(ts));
            }
        }
        stringRedisTemplate.execute(FEED_BULK_SCRIPT, Collections.singletonList(outboxKey), args.toArray());
    }

    /**
     * 将晋升的大V ID 写入老粉丝的 famousFollows:{fanId} 集合。
     */
    private void addAuthorToFollowersFamousSet(Long authorId) {
        List<Follow> follows = followService.query().eq("follow_user_id", authorId).list();
        if (follows == null || follows.isEmpty()) {
            return;
        }
        String authorIdStr = authorId.toString();
        // 分批执行，每批 500 个粉丝一次 Pipeline
        int batchSize = 500;
        List<Long> fanIds = follows.stream().map(Follow::getUserId).toList();
        for (int i = 0; i < fanIds.size(); i += batchSize) {
            int toIndex = Math.min(i + batchSize, fanIds.size());
            List<Long> subList = fanIds.subList(i, toIndex);
            stringRedisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                for (Long fanId : subList) {
                    byte[] key = (RedisConstants.FAMOUS_FOLLOW_KEY + fanId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    connection.setCommands().sAdd(key, authorIdStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
        }
    }
}
