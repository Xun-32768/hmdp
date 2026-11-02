package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 交换机名称
    public static final String SECKILL_EXCHANGE = "seckill.direct";
    // 队列名称
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    // 路由键
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true); // true 表示持久化
    }

    @Bean
    public Binding seckillBinding(@Qualifier("seckillQueue") Queue seckillQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with(SECKILL_ROUTING_KEY);
    }

    // 配置 JSON 序列化器，发送 Java 对象时会自动转为 JSON
    @Bean
    public MessageConverter messageConverter(){
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public TopicExchange feedExchange() {
        return new TopicExchange("feed.topic");
    }
    @Bean
    public Queue feedRebuildQueue() {
        // 建议加上 TTL 或长度限制，防止极端情况下内存撑爆
        return new Queue("feed.rebuild.queue");
    }
    @Bean
    public Binding feedBinding() {
        return BindingBuilder.bind(feedRebuildQueue()).to(feedExchange()).with("feed.rebuild.#");
    }
}