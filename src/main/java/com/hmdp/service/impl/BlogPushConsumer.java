package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.dto.BlogPushDTO;
import com.hmdp.pojo.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Feed 写扩散消费者（推拉结合中的「推」）。
 *
 * <p>只负责小博主的粉丝分发：
 * <ol>
 *   <li>取小博主粉丝列表（优先 Redis {@code fans:{authorId}}，缺失回退 MySQL）；</li>
 *   <li>按批次拆分粉丝，每批通过一次 Pipeline 下发 Lua 脚本；</li>
 *   <li>由 Lua 原子完成 EXISTS 过滤（只推活跃用户）、ZADD、裁剪与续期。</li>
 * </ol>
 *
 * <p>大V 的动态不进入本消费链路，由刷流时实时拉取发件箱（读扩散）。
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Component
public class BlogPushConsumer {

    /** 单批处理的收件箱数量，控制单次 Pipeline 的命令量 */
    private static final int BATCH_SIZE = 500;

    /** 收件箱写扩散脚本（EXISTS 过滤 + ZADD + 裁剪 + EXPIRE，原子执行） */
    private static final DefaultRedisScript<Long> FEED_PUSH_SCRIPT;
    private static final byte[] SCRIPT_BYTES;

    static {
        FEED_PUSH_SCRIPT = new DefaultRedisScript<>();
        FEED_PUSH_SCRIPT.setLocation(new ClassPathResource("feed_push.lua"));
        FEED_PUSH_SCRIPT.setResultType(Long.class);
        try {
            FEED_PUSH_SCRIPT.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 feed_push.lua 失败", e);
        }
        SCRIPT_BYTES = FEED_PUSH_SCRIPT.getScriptAsString().getBytes(StandardCharsets.UTF_8);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 消费博客发布事件，把笔记推送到粉丝收件箱。
     *
     * @param dto         推送事件
     * @param message     原始消息
     * @param channel     AMQP 信道
     * @param deliveryTag 投递标签
     * @param retryCount  已重试次数（来自消息头，首次消费为 {@code null}）
     * @throws IOException ACK / NACK 失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.BLOG_FEED_PUSH_QUEUE)
    public void listenPush(@Payload BlogPushDTO dto,
                           Message message,
                           Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                           @Header(value = RabbitMQConfig.FEED_HEADER_RETRY_COUNT, required = false) Integer retryCount)
            throws IOException {
        log.info("BlogPushConsumer.listenPush received: dto={}", dto);
        if (dto == null || dto.getBlogId() == null || dto.getAuthorId() == null) {
            // 非法消息直接丢弃，避免无限重投
            channel.basicAck(deliveryTag, false);
            return;
        }
        try {
            int pushed = pushToFanInboxes(dto);
            log.info("Feed 写扩散完成：blogId={}, authorId={}, 实际推送收件箱数={}",
                    dto.getBlogId(), dto.getAuthorId(), pushed);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            int retried = retryCount == null ? 0 : retryCount;
            log.error("Feed 写扩散失败，blogId={}, authorId={}, 已重试={}",
                    dto.getBlogId(), dto.getAuthorId(), retried, e);
            if (retried < RabbitMQConfig.FEED_MAX_RETRY) {
                // 先进 TTL 延迟重试队列，避免 requeue=true 立刻空转重投把消费者打满
                if (publishToRetryQueue(dto, retried + 1)) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    // 重投失败：保留未确认状态，连接恢复后重回队列
                    channel.basicNack(deliveryTag, false, true);
                }
            } else if (publishToDlq(dto, "写扩散重试次数超限")) {
                channel.basicAck(deliveryTag, false);
            } else {
                log.error("死信投递失败，保留未确认消息等待重投，blogId={}", dto.getBlogId());
            }
        }
    }

    /**
     * 投递到写扩散延迟重试队列。
     *
     * <p>消息在重试队列中等待 TTL 到期后，作为死信回流到业务交换机重新消费。
     *
     * @param dto            推送事件
     * @param nextRetryCount 本次重试序号，写入消息头供下次消费读取
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToRetryQueue(BlogPushDTO dto, int nextRetryCount) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.BLOG_FEED_RETRY_EXCHANGE,
                    RabbitMQConfig.BLOG_FEED_RETRY_ROUTING_KEY,
                    dto,
                    message -> {
                        message.getMessageProperties()
                                .setHeader(RabbitMQConfig.FEED_HEADER_RETRY_COUNT, nextRetryCount);
                        return message;
                    }
            );
            log.warn("Feed 写扩散转入延迟重试，blogId={}, 第 {} 次重试", dto.getBlogId(), nextRetryCount);
            return true;
        } catch (Exception e) {
            log.error("Feed 写扩散投递重试队列失败，blogId={}", dto.getBlogId(), e);
            return false;
        }
    }

    /**
     * 手工投递到死信交换机。
     *
     * <p>用于需要附加失败原因的场景，便于人工排查与补推。
     *
     * @param dto    推送事件
     * @param reason 失败原因
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToDlq(BlogPushDTO dto, String reason) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.BLOG_FEED_DLX,
                    RabbitMQConfig.BLOG_FEED_DLQ_ROUTING_KEY,
                    dto,
                    message -> {
                        message.getMessageProperties().setHeader("x-fail-reason", reason);
                        return message;
                    }
            );
            log.error("Feed 写扩散转入死信队列，blogId={}, 原因={}", dto.getBlogId(), reason);
            return true;
        } catch (Exception e) {
            log.error("Feed 写扩散死信投递失败，blogId={}", dto.getBlogId(), e);
            return false;
        }
    }

    /**
     * 把小博主的笔记推送到其粉丝收件箱。
     *
     * @param dto 推送事件
     * @return 实际写入的收件箱数量
     */
    private int pushToFanInboxes(BlogPushDTO dto) {
        Long authorId = dto.getAuthorId();

        // 双重兜底：消费时再判一次是否已晋升大V，避免消息在队列中滞留期间身份变化
        UserInfo info = userInfoService.query().eq("user_id", authorId).one();
        int fans = (info == null || info.getFans() == null) ? 0 : info.getFans();
        if (fans >= RedisConstants.FAMOUS_FAN_THRESHOLD) {
            log.info("博主已晋升大V，跳过写扩散，改由粉丝拉取：authorId={}, fans={}", authorId, fans);
            return 0;
        }

        List<String> fanIds = listFanIds(authorId);
        if (fanIds.isEmpty()) {
            return 0;
        }

        String member = dto.getBlogId().toString();
        String score = String.valueOf(dto.getPublishTime() == null
                ? System.currentTimeMillis() : dto.getPublishTime());
        String maxLen = String.valueOf(RedisConstants.FEED_MAX_LEN);
        String ttlSeconds = String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.FEED_INBOX_TTL_DAYS));

        int pushed = 0;
        for (int from = 0; from < fanIds.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, fanIds.size());
            List<String> batch = fanIds.subList(from, to);
            pushed += pushBatch(batch, member, score, maxLen, ttlSeconds);
        }
        return pushed;
    }

    /**
     * 单批推送：一次 Pipeline 下发全部 Lua 脚本。
     *
     * <p><b>注意</b>：不能在 Pipeline 回调里判断 {@code EXISTS} 的返回值，
     * 此时命令只是入队，返回值尚未回填（必然为 null）。因此把判断逻辑放进 Lua 原子执行。
     *
     * @param fanIds     本批粉丝 ID
     * @param member     笔记 ID
     * @param score      发布时间戳
     * @param maxLen     收件箱最大条数
     * @param ttlSeconds 收件箱 TTL（秒）
     * @return 本批实际写入的收件箱数量
     */
    @SuppressWarnings("unchecked")
    private int pushBatch(List<String> fanIds, String member, String score, String maxLen, String ttlSeconds) {
        final byte[] scriptBytes = SCRIPT_BYTES;
        final byte[] memberBytes = member.getBytes(StandardCharsets.UTF_8);
        final byte[] scoreBytes = score.getBytes(StandardCharsets.UTF_8);
        final byte[] maxLenBytes = maxLen.getBytes(StandardCharsets.UTF_8);
        final byte[] ttlBytes = ttlSeconds.getBytes(StandardCharsets.UTF_8);
        final byte[] skipIfAbsent = "0".getBytes(StandardCharsets.UTF_8);

        // 用 RedisCallback 走原生 EVAL：executePipelined 会按命令顺序把结果回填到 List 中
        List<Object> results = stringRedisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) {
                for (String fanId : fanIds) {
                    byte[] key = (RedisConstants.FEED_KEY + fanId).getBytes(StandardCharsets.UTF_8);
                    byte[][] keysAndArgs = {key, memberBytes, scoreBytes, maxLenBytes, ttlBytes, skipIfAbsent};
                    connection.scriptingCommands().eval(scriptBytes, ReturnType.INTEGER, 1, keysAndArgs);
                }
                // Pipeline 必须返回 null，结果由框架按命令顺序回填
                return null;
            }
        });
        int sum = 0;
        if (results != null) {
            for (Object r : results) {
                if (r instanceof Number n) {
                    sum += n.intValue();
                }
            }
        }
        return sum;
    }

    /**
     * 获取小博主粉丝 ID 列表。
     *
     * <p>优先读 Redis {@code fans:{authorId}}（关注时写入，受控小 Key），
     * 缺失时回退 MySQL {@code tb_follow} 并顺带回填缓存。
     *
     * @param authorId 博主 ID
     * @return 粉丝用户 ID 列表
     */
    private List<String> listFanIds(Long authorId) {
        String fansKey = RedisConstants.FANS_KEY + authorId;
        Set<String> cached = stringRedisTemplate.opsForSet().members(fansKey);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<>(cached);
        }
        // 缓存缺失：从 DB 回查，避免漏推
        List<String> fanIds = followService.query()
                .eq("follow_user_id", authorId)
                .list()
                .stream()
                .map(f -> f.getUserId().toString())
                .toList();
        if (!fanIds.isEmpty()) {
            stringRedisTemplate.opsForSet().add(fansKey, fanIds.toArray(new String[0]));
        }
        return fanIds;
    }
}