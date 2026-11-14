package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.SystemConstants.bizNow;

/**
 * 秒杀链路定时维护任务。
 *
 * <p>包含两个互相独立的巡检任务：
 * <ol>
 *   <li>{@link #reconcileStock()} —— 库存对账，修复 Redis 库存缺失或与 DB 不一致</li>
 * </ol>
 *
 * <p>注：订单超时自动取消已全面移交至 RabbitMQ 15分钟延迟队列及死信消费者处理，不再依赖定时扫表。
 *
 * <p>Redis key 的过期清理依赖自身 TTL，不需要额外任务。
 *
 * <p><b>多实例部署注意</b>：{@code @Scheduled} 在每个实例上都会触发，
 * 如需避免重复执行，需自行加分布式锁（如 Redisson）或改用调度中心。
 */
@Slf4j
@Component
public class SeckillMaintenanceTask {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AmqpAdmin amqpAdmin;

    /**
     * 库存对账（每 10 分钟执行）。
     *
     * <p>只处理尚未结束的活动：Redis 库存 key 缺失时按 DB 重建（含时间窗口）；
     * 两者都存在但不一致时以 DB 为准覆盖并告警，避免超卖。
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void reconcileStock() {
        // 统一使用业务时区，避免 JVM 默认时区（如容器内 UTC）与 begin/end_time 字面量错位
        LocalDateTime now = bizNow();
        List<SeckillVoucher> vouchers = seckillVoucherService.query()
                .gt("end_time", now)
                .list();
        if (vouchers.isEmpty()) {
            return;
        }
        for (SeckillVoucher sv : vouchers) {
            Long voucherId = sv.getVoucherId();
            String stockKey = SECKILL_STOCK_KEY + voucherId;
            String redisStock = stringRedisTemplate.opsForValue().get(stockKey);
            if (redisStock == null) {
                log.warn("Redis 秒杀库存缺失，按数据库重建：voucherId={}, dbStock={}", voucherId, sv.getStock());
                voucherService.cacheSeckillVoucher(voucherId, sv.getStock(),
                        sv.getBeginTime(), sv.getEndTime());
                continue;
            }
            try {
                int cacheStock = Integer.parseInt(redisStock);
                if (cacheStock != sv.getStock()) {
                    log.warn("秒杀库存不一致（Redis 与 DB），voucherId={}, redisStock={}, dbStock={}",
                            voucherId, cacheStock, sv.getStock());
                    // 以数据库为准修正 Redis，避免超卖
                    stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(sv.getStock()));
                }
            } catch (NumberFormatException e) {
                log.error("Redis 秒杀库存值非法，重建：voucherId={}, value={}", voucherId, redisStock);
                voucherService.cacheSeckillVoucher(voucherId, sv.getStock(),
                        sv.getBeginTime(), sv.getEndTime());
            }
        }
    }
}