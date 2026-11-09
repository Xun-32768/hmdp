package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.entity.SeckillVoucher;
import com.hmdp.pojo.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
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

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_TIMEOUT_MINUTES;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.SystemConstants.bizNow;

/**
 * 秒杀链路定时维护任务。
 *
 * <p>包含三个互相独立的巡检任务：
 * <ol>
 *   <li>{@link #reconcileStock()} —— 库存对账，修复 Redis 库存缺失或与 DB 不一致</li>
 *   <li>{@link #closeTimeoutOrders()} —— 超时关单，取消未支付订单并归还库存</li>
 *   <li>{@link #checkDlqBacklog()} —— 死信积压告警，只告警不自动重放</li>
 * </ol>
 *
 * <p>Redis key 的过期清理依赖自身 TTL，不需要额外任务。
 *
 * <p><b>多实例部署注意</b>：{@code @Scheduled} 在每个实例上都会触发，
 * 如需避免重复执行，需自行加分布式锁（如 Redisson）或改用调度中心。
 */
@Slf4j
@Component
public class SeckillMaintenanceTask {

    /** 订单状态：未支付 */
    private static final byte ORDER_STATUS_UNPAID = 1;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillOrderCancelService cancelService;

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

    /**
     * 超时关单（每分钟执行）。
     *
     * <p>未支付订单超过 {@code SECKILL_ORDER_TIMEOUT_MINUTES} 分钟自动取消，
     * 同时归还 DB 与 Redis 库存并解除用户下单标记；
     * 若不解除标记，该用户会被 Lua 的一人一单校验永久拦住。
     *
     * <p>单笔失败只记录日志，不影响其他订单处理。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void closeTimeoutOrders() {
        LocalDateTime deadline = bizNow().minusMinutes(SECKILL_ORDER_TIMEOUT_MINUTES);
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getStatus, ORDER_STATUS_UNPAID)
                .lt(VoucherOrder::getCreateTime, deadline);
        List<VoucherOrder> timeoutOrders = voucherOrderService.list(wrapper);
        if (timeoutOrders.isEmpty()) {
            return;
        }
        for (VoucherOrder order : timeoutOrders) {
            try {
                cancelService.cancelOrder(order);
            } catch (Exception e) {
                log.error("超时关单失败，orderId={}", order.getId(), e);
            }
        }
        log.info("超时关单完成，共处理 {} 笔", timeoutOrders.size());
    }

    /**
     * 死信队列积压告警（每 5 分钟执行）。
     *
     * <p>死信消息代表「Redis 已预扣但订单未落库」，必须尽快人工处理。
     * 这里只做告警（日志 + 计数），不自动重放，避免掩盖真实故障。
     *
     * <p>同时检查死信队列深度与 Redis 审计记录条数，任一项非零即告警。
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void checkDlqBacklog() {
        Long depth = stringRedisTemplate.opsForList().size(RabbitMQConfig.SECKILL_DLQ_RECORD_KEY);
        long dlqCount = 0L;
        try {
            // getQueueInfo 返回 QueueInformation，可直接读取积压条数
            QueueInformation info = amqpAdmin.getQueueInfo(RabbitMQConfig.SECKILL_DLQ);
            if (info != null) {
                dlqCount = info.getMessageCount();
            }
        } catch (Exception e) {
            log.warn("查询死信队列深度失败", e);
        }
        if (dlqCount > 0 || (depth != null && depth > 0)) {
            log.error("【秒杀死信积压告警】死信队列待处理 {} 条，Redis 审计记录 {} 条，请尽快人工核对补单",
                    dlqCount, depth == null ? 0 : depth);
        }
    }
}