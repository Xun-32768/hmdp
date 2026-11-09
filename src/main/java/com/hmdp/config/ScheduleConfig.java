package com.hmdp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务（秒杀库存对账、活动清理、订单超时关单）
 */
@Configuration
@EnableScheduling
public class  ScheduleConfig {
}