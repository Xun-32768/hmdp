package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑配置（秒杀订单链路 + 博客 Feed 重建）。
 *
 * <p><b>秒杀主链路</b>：{@code seckill.direct} → {@code seckill.order.queue}，
 * 消费失败时按失败类型分流为「延迟重试」或「死信」。
 *
 * <p><b>延迟重试</b>：消息投到 {@code seckill.retry.direct} → {@code seckill.order.retry.queue}，
 * 借助队列的 {@code x-message-ttl} + 死信路由实现延迟回投，
 * 避免 {@code basicNack(requeue=true)} 立刻重投造成空转打满 CPU。
 *
 * <p><b>死信</b>：{@code seckill.dlx} → {@code seckill.order.dlq}，只审计不自动重放。
 *
 * <p><b>升级注意</b>：给已存在的队列新增/修改参数（如 x-dead-letter-*、x-message-ttl）
 * 会让 broker 报 {@code PRECONDITION_FAILED}，
 * 升级时需先删除旧队列或修改队列名。
 *
 * @author axun
 * @since 2026-02-01
 */
@Configuration
public class RabbitMQConfig {

    /* ==================== 秒杀主链路 ==================== */

    /** 秒杀业务交换机（direct） */
    public static final String SECKILL_EXCHANGE = "seckill.direct";

    /** 秒杀业务队列，实际落库消费者监听此队列 */
    public static final String SECKILL_QUEUE = "seckill.order.queue";

    /** 秒杀业务路由键 */
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    /* ==================== 死信队列 ==================== */

    /** 死信交换机：业务队列无法处理的消息统一投递到这里 */
    public static final String SECKILL_DLX = "seckill.dlx";
    /** 死信队列：保留所有最终失败的消息，供人工排查与重放 */
    public static final String SECKILL_DLQ = "seckill.order.dlq";
    /** 死信路由键 */
    public static final String SECKILL_DLQ_ROUTING_KEY = "seckill.order.dlq";
    /** 死信审计记录（Redis List，保存最近若干条失败消息） */
    public static final String SECKILL_DLQ_RECORD_KEY = "seckill:dlq:record";

    /* ==================== 延迟重试队列 ==================== */

    /** 重试交换机 */
    public static final String SECKILL_RETRY_EXCHANGE = "seckill.retry.direct";
    /** 重试队列：消息在此等待 TTL 到期后由死信路由自动回到业务队列 */
    public static final String SECKILL_RETRY_QUEUE = "seckill.order.retry.queue";
    /** 重试路由键 */
    public static final String SECKILL_RETRY_ROUTING_KEY = "seckill.order.retry";
    /** 重试间隔（毫秒） */
    public static final long SECKILL_RETRY_DELAY_MILLIS = 5_000L;
    /** 最大重试次数，超过后进入死信队列 */
    public static final int SECKILL_MAX_RETRY = 3;

    /** 消息头：已重试次数 */
    public static final String HEADER_RETRY_COUNT = "x-seckill-retry-count";
    /** 消息头：进入死信的原因 */
    public static final String HEADER_FAIL_REASON = "x-seckill-fail-reason";

    /* ==================== 15分钟订单超时未支付延迟队列 ==================== */
    /** 订单超时延迟暂存队列（无人消费，TTL 到期后作为死信路由到 seckill.dlx） */
    public static final String SECKILL_TIMEOUT_DELAY_QUEUE = "seckill.order.timeout.delay.queue";
    /** 订单超时延迟消息路由键 */
    public static final String SECKILL_TIMEOUT_DELAY_ROUTING_KEY = "seckill.order.timeout.delay";
    /** 订单超时时长（15分钟，单位毫秒） */
    public static final long SECKILL_ORDER_TTL_MILLIS = 15 * 60 * 1000L;

    /* ==================== 重试超限 / 确定性失败 ==================== */

    /**
     * 秒杀业务主交换机。
     *
     * @return direct 类型交换机，与 {@link #SECKILL_QUEUE} 通过 {@link #SECKILL_ROUTING_KEY} 绑定
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    /**
     * 秒杀业务队列。
     *
     * <p>挂载死信交换机后，即便是「监听器抛异常被 reject」「消息过期」这类
     * 未被代码显式处理的情况，消息也会转入死信队列而不是被丢弃。
     *
     * @return 持久化队列，带死信路由
     */
    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE)
                .deadLetterExchange(SECKILL_DLX)
                .deadLetterRoutingKey(SECKILL_DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * 把业务队列绑定到业务交换机。
     *
     * @param seckillQueue    业务队列
     * @param seckillExchange 业务交换机
     * @return 绑定关系
     */
    @Bean
    public Binding seckillBinding(@Qualifier("seckillQueue") Queue seckillQueue,
                                  @Qualifier("seckillExchange") DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with(SECKILL_ROUTING_KEY);
    }

    /**
     * 死信交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange seckillDlx() {
        return new DirectExchange(SECKILL_DLX);
    }

    /**
     * 死信队列（最终失败消息的落脚点）。
     *
     * <p>不设 TTL、不设长度限制：死信必须保留到人工处理完，
     * 堆压由 {@code SeckillMaintenanceTask#checkDlqBacklog} 告警。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue seckillDlq() {
        return QueueBuilder.durable(SECKILL_DLQ).build();
    }

    /**
     * 把死信队列绑定到死信交换机。
     *
     * @param seckillDlq 死信队列
     * @param seckillDlx 死信交换机
     * @return 绑定关系
     */
    @Bean
    public Binding seckillDlqBinding(@Qualifier("seckillDlq") Queue seckillDlq,
                                     @Qualifier("seckillDlx") DirectExchange seckillDlx) {
        return BindingBuilder.bind(seckillDlq).to(seckillDlx).with(SECKILL_DLQ_ROUTING_KEY);
    }

    /**
     * 延迟重试交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange seckillRetryExchange() {
        return new DirectExchange(SECKILL_RETRY_EXCHANGE);
    }

    /**
     * 延迟重试队列。
     *
     * <p>消息进入后等待 {@link #SECKILL_RETRY_DELAY_MILLIS} 毫秒（{@code x-message-ttl}），
     * 到期后作为死信被路由回业务交换机，实现「延迟 + 有限次重试」，
     * 避免用 {@code requeue=true} 造成空转打满 CPU。
     *
     * @return 持久化队列，带 TTL 与回投死信路由
     */
    @Bean
    public Queue seckillRetryQueue() {
        return QueueBuilder.durable(SECKILL_RETRY_QUEUE)
                .withArgument("x-message-ttl", (int) SECKILL_RETRY_DELAY_MILLIS)
                .deadLetterExchange(SECKILL_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ROUTING_KEY)
                .build();
    }

    /**
     * 把重试队列绑定到重试交换机。
     *
     * @param seckillRetryQueue    重试队列
     * @param seckillRetryExchange 重试交换机
     * @return 绑定关系
     */
    @Bean
    public Binding seckillRetryBinding(@Qualifier("seckillRetryQueue") Queue seckillRetryQueue,
                                      @Qualifier("seckillRetryExchange") DirectExchange seckillRetryExchange) {
        return BindingBuilder.bind(seckillRetryQueue).to(seckillRetryExchange).with(SECKILL_RETRY_ROUTING_KEY);
    }

    /**
     * JSON 消息序列化器，使 {@code convertAndSend} 可以直接发送 Java 对象。
     *
     * @return Jackson 实现的消息转换器
     */
    @Bean
    public MessageConverter messageConverter(){
        return new Jackson2JsonMessageConverter();
    }

    /* ==================== 博客 Feed 推送与重建 ==================== */
    /** 博客 Feed 推送交换机（direct） */
    public static final String BLOG_FEED_EXCHANGE = "blog.feed.direct";
    /** 博客 Feed 异步写扩散推送队列（v2：带延迟重试与死信） */
    public static final String BLOG_FEED_PUSH_QUEUE = "blog.feed.push.v2.queue";
    /** 博客 Feed 推送路由键 */
    public static final String BLOG_FEED_PUSH_ROUTING_KEY = "blog.feed.push";

    /** Feed 推送死信交换机 */
    public static final String BLOG_FEED_DLX = "blog.feed.dlx";
    /** Feed 推送死信队列：写扩散最终失败的消息留档，供人工补推 */
    public static final String BLOG_FEED_DLQ = "blog.feed.v2.dlq";
    /** Feed 推送死信路由键 */
    public static final String BLOG_FEED_DLQ_ROUTING_KEY = "blog.feed.dlq";

    /** Feed 推送重试交换机 */
    public static final String BLOG_FEED_RETRY_EXCHANGE = "blog.feed.retry.direct";
    /** Feed 推送重试队列：TTL 到期后作为死信回投业务队列 */
    public static final String BLOG_FEED_RETRY_QUEUE = "blog.feed.push.retry.v2.queue";
    /** Feed 推送重试路由键 */
    public static final String BLOG_FEED_RETRY_ROUTING_KEY = "blog.feed.push.retry";
    /** 写扩散重试间隔（毫秒），写扩散本身耗时长，间隔比秒杀稍大 */
    public static final long BLOG_FEED_RETRY_DELAY_MILLIS = 10_000L;

    /** Feed 链路重试计数消息头（写扩散与重建共用） */
    public static final String FEED_HEADER_RETRY_COUNT = "x-feed-retry-count";
    /** Feed 链路最大重试次数，超限后转入死信队列 */
    public static final int FEED_MAX_RETRY = 3;

    /** Feed 重建交换机（topic） */
    public static final String FEED_REBUILD_EXCHANGE = "feed.topic";
    /** Feed 重建路由键 */
    public static final String FEED_REBUILD_ROUTING_KEY = "feed.rebuild";
    /** Feed 重建队列（v2：带死信） */
    public static final String FEED_REBUILD_QUEUE = "feed.rebuild.v2.queue";
    /** Feed 重建死信交换机 */
    public static final String FEED_REBUILD_DLX = "feed.rebuild.dlx";
    /** Feed 重建死信队列 */
    public static final String FEED_REBUILD_DLQ = "feed.rebuild.v2.dlq";
    /** Feed 重建死信路由键 */
    public static final String FEED_REBUILD_DLQ_ROUTING_KEY = "feed.rebuild.dlq";

    /** Feed 重建重试交换机 */
    public static final String FEED_REBUILD_RETRY_EXCHANGE = "feed.rebuild.retry.direct";
    /** Feed 重建重试队列：TTL 到期后作为死信回投 {@link #FEED_REBUILD_EXCHANGE} */
    public static final String FEED_REBUILD_RETRY_QUEUE = "feed.rebuild.retry.v2.queue";
    /** Feed 重建重试路由键 */
    public static final String FEED_REBUILD_RETRY_ROUTING_KEY = "feed.rebuild.retry";

    /** 大V晋升迁移队列 */
    public static final String FEED_UPGRADE_QUEUE = "feed.upgrade.queue";
    /** 大V晋升路由键 */
    public static final String FEED_UPGRADE_ROUTING_KEY = "feed.upgrade";

    /**
     * 博客 Feed 重建交换机。
     *
     * @return topic 类型交换机
     */
    @Bean
    public TopicExchange feedExchange() {
        return new TopicExchange(FEED_REBUILD_EXCHANGE);
    }

    /**
     * 博客 Feed 推送交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange blogFeedExchange() {
        return new DirectExchange(BLOG_FEED_EXCHANGE);
    }

    /**
     * 博客 Feed 异步写扩散推送队列。
     *
     * <p>挂载死信路由：消费 NACK 且重试超限或队列内消息过期时，
     * 转入 {@link #BLOG_FEED_DLQ} 留档，不会静默丢失。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue blogFeedPushQueue() {
        return QueueBuilder.durable(BLOG_FEED_PUSH_QUEUE)
                .deadLetterExchange(BLOG_FEED_DLX)
                .deadLetterRoutingKey(BLOG_FEED_DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * 绑定博客 Feed 推送队列与交换机。
     *
     * @param blogFeedPushQueue 推送队列
     * @param blogFeedExchange  推送交换机
     * @return 绑定关系
     */
    @Bean
    public Binding blogFeedPushBinding(@Qualifier("blogFeedPushQueue") Queue blogFeedPushQueue,
                                       @Qualifier("blogFeedExchange") DirectExchange blogFeedExchange) {
        return BindingBuilder.bind(blogFeedPushQueue).to(blogFeedExchange).with(BLOG_FEED_PUSH_ROUTING_KEY);
    }

    /**
     * Feed 推送死信交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange blogFeedDlx() {
        return new DirectExchange(BLOG_FEED_DLX);
    }

    /**
     * Feed 推送死信队列。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue blogFeedDlq() {
        return QueueBuilder.durable(BLOG_FEED_DLQ).build();
    }

    /**
     * 绑定 Feed 推送死信队列。
     *
     * @param blogFeedDlq 死信队列
     * @param blogFeedDlx 死信交换机
     * @return 绑定关系
     */
    @Bean
    public Binding blogFeedDlqBinding(@Qualifier("blogFeedDlq") Queue blogFeedDlq,
                                      @Qualifier("blogFeedDlx") DirectExchange blogFeedDlx) {
        return BindingBuilder.bind(blogFeedDlq).to(blogFeedDlx).with(BLOG_FEED_DLQ_ROUTING_KEY);
    }

    /**
     * Feed 推送重试交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange blogFeedRetryExchange() {
        return new DirectExchange(BLOG_FEED_RETRY_EXCHANGE);
    }

    /**
     * Feed 推送重试队列。
     *
     * <p>消息在此等待 {@link #BLOG_FEED_RETRY_DELAY_MILLIS} 毫秒后，
     * 作为死信回流到业务交换机，避免 {@code basicNack(requeue=true)} 立刻空转重投。
     *
     * @return 持久化队列，带 TTL 与回投死信路由
     */
    @Bean
    public Queue blogFeedRetryQueue() {
        return QueueBuilder.durable(BLOG_FEED_RETRY_QUEUE)
                .withArgument("x-message-ttl", (int) BLOG_FEED_RETRY_DELAY_MILLIS)
                .deadLetterExchange(BLOG_FEED_EXCHANGE)
                .deadLetterRoutingKey(BLOG_FEED_PUSH_ROUTING_KEY)
                .build();
    }

    /**
     * 绑定 Feed 推送重试队列。
     *
     * @param blogFeedRetryQueue    重试队列
     * @param blogFeedRetryExchange 重试交换机
     * @return 绑定关系
     */
    @Bean
    public Binding blogFeedRetryBinding(@Qualifier("blogFeedRetryQueue") Queue blogFeedRetryQueue,
                                        @Qualifier("blogFeedRetryExchange") DirectExchange blogFeedRetryExchange) {
        return BindingBuilder.bind(blogFeedRetryQueue).to(blogFeedRetryExchange).with(BLOG_FEED_RETRY_ROUTING_KEY);
    }

    /**
     * 博客 Feed 重建队列。
     *
     * <p>重建由「收件箱不存在」触发，属幂等操作；失败时先进延迟重试，
     * 重试超限后转死信，不影响刷流主链路。
     *
     * @return 持久化队列，带死信路由
     */
    @Bean
    public Queue feedRebuildQueue() {
        return QueueBuilder.durable(FEED_REBUILD_QUEUE)
                .deadLetterExchange(FEED_REBUILD_DLX)
                .deadLetterRoutingKey(FEED_REBUILD_DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * 把 Feed 重建队列绑定到 Feed 交换机（匹配 {@code feed.rebuild.#}）。
     *
     * @param feedRebuildQueue 重建队列
     * @param feedExchange     重建交换机
     * @return 绑定关系
     */
    @Bean
    public Binding feedBinding(@Qualifier("feedRebuildQueue") Queue feedRebuildQueue,
                               @Qualifier("feedExchange") TopicExchange feedExchange) {
        return BindingBuilder.bind(feedRebuildQueue).to(feedExchange).with("feed.rebuild.#");
    }

    /**
     * Feed 重建死信交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange feedRebuildDlx() {
        return new DirectExchange(FEED_REBUILD_DLX);
    }

    /**
     * Feed 重建死信队列。
     *
     * @return 持久化队列
     */
    @Bean
    public Queue feedRebuildDlq() {
        return QueueBuilder.durable(FEED_REBUILD_DLQ).build();
    }

    /**
     * 绑定 Feed 重建死信队列。
     *
     * @param feedRebuildDlq 死信队列
     * @param feedRebuildDlx 死信交换机
     * @return 绑定关系
     */
    @Bean
    public Binding feedRebuildDlqBinding(@Qualifier("feedRebuildDlq") Queue feedRebuildDlq,
                                         @Qualifier("feedRebuildDlx") DirectExchange feedRebuildDlx) {
        return BindingBuilder.bind(feedRebuildDlq).to(feedRebuildDlx).with(FEED_REBUILD_DLQ_ROUTING_KEY);
    }

    /**
     * Feed 重建重试交换机。
     *
     * @return direct 类型交换机
     */
    @Bean
    public DirectExchange feedRebuildRetryExchange() {
        return new DirectExchange(FEED_REBUILD_RETRY_EXCHANGE);
    }

    /**
     * Feed 重建重试队列。
     *
     * <p>与写扩散重试一致：消息在此等待 {@link #BLOG_FEED_RETRY_DELAY_MILLIS} 毫秒，
     * 过期后作为死信回投 {@link #FEED_REBUILD_EXCHANGE}，实现延迟重试。
     *
     * @return 持久化队列，带 TTL 与回投死信路由
     */
    @Bean
    public Queue feedRebuildRetryQueue() {
        return QueueBuilder.durable(FEED_REBUILD_RETRY_QUEUE)
                .withArgument("x-message-ttl", (int) BLOG_FEED_RETRY_DELAY_MILLIS)
                .deadLetterExchange(FEED_REBUILD_EXCHANGE)
                .deadLetterRoutingKey(FEED_REBUILD_ROUTING_KEY)
                .build();
    }

    /**
     * 绑定 Feed 重建重试队列。
     *
     * @param feedRebuildRetryQueue    重建重试队列
     * @param feedRebuildRetryExchange 重建重试交换机
     * @return 绑定关系
     */
    @Bean
    public Binding feedRebuildRetryBinding(@Qualifier("feedRebuildRetryQueue") Queue feedRebuildRetryQueue,
                                           @Qualifier("feedRebuildRetryExchange") DirectExchange feedRebuildRetryExchange) {
        return BindingBuilder.bind(feedRebuildRetryQueue).to(feedRebuildRetryExchange)
                .with(FEED_REBUILD_RETRY_ROUTING_KEY);
    }

    /**
     * 大V晋升迁移队列。
     *
     * @return 迁移队列
     */
    @Bean
    public Queue feedUpgradeQueue() {
        return QueueBuilder.durable(FEED_UPGRADE_QUEUE).build();
    }

    /**
     * 绑定大V晋升迁移队列到 feed.topic 交换机。
     *
     * @param feedUpgradeQueue 晋升迁移队列
     * @param feedExchange     feed.topic 交换机
     * @return 绑定关系
     */
    @Bean
    public Binding feedUpgradeBinding(@Qualifier("feedUpgradeQueue") Queue feedUpgradeQueue,
                                      @Qualifier("feedExchange") TopicExchange feedExchange) {
        return BindingBuilder.bind(feedUpgradeQueue).to(feedExchange).with(FEED_UPGRADE_ROUTING_KEY);
    }
}