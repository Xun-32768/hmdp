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

    /**
     * 博客 Feed 重建交换机。
     *
     * @return topic 类型交换机
     */
    @Bean
    public TopicExchange feedExchange() {
        return new TopicExchange("feed.topic");
    }

    /**
     * 博客 Feed 重建队列。
     *
     * <p>当前未设置 TTL 与长度限制，极端情况下可能堆积，建议后续加上上限。
     *
     * @return 重建队列
     */
    @Bean
    public Queue feedRebuildQueue() {
        return new Queue("feed.rebuild.queue");
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
}