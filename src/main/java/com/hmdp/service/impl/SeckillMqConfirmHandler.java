package com.hmdp.service.impl;

import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀订单消息的投递确认处理器。
 *
 * <p><b>为什么需要它</b>：Lua 脚本执行成功时就已经在 Redis 里预扣了库存、标记了用户。
 * 如果消息最终没能进入队列（MQ 不可达、交换机无匹配队列等），
 * 用户就会陷入「显示抢到但永远没有订单」的状态。
 * 这里通过 publisher-confirm / publisher-returns 回调捕捉失败，并回滚 Redis 预扣。
 *
 * <p><b>回调时机</b>：RabbitMQ 保证每条消息最终都会有一次 confirm 回调
 * （被退回的消息随后也会 confirm ack），因此统一在 confirm 回调里清理登记，
 * 并借助 {@link CorrelationData#getReturned()} 判断该消息是否被退回。
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Component
public class SeckillMqConfirmHandler implements RabbitTemplate.ConfirmCallback {

    /** 待确认记录的兜底存活时间，超过此时长仍未收到 confirm 视为异常并核对处理 */
    private static final long PENDING_TIMEOUT_MILLIS = 60_000L;

    /** 待确认记录容量警戒水位，超过时输出 ERROR 告警存在积压 */
    private static final int PENDING_WARN_THRESHOLD = 5000;

    /** 回滚脚本，与主链路共用 seckill_rollback.lua */
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    /** orderId -> 待确认信息 */
    private final Map<Long, PendingInfo> pending = new ConcurrentHashMap<>();

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
    }

    /**
     * 登记一条待确认消息。
     *
     * <p><b>必须在 {@code convertAndSend} 之前调用</b>：
     * 否则 confirm 回调可能早于登记到达，导致待确认表里查不到记录而漏掉回滚。
     *
     * @param orderId   订单号，同时作为 CorrelationData 的 id
     * @param voucherId 优惠券 id，回滚时定位库存 key
     * @param userId    用户 id，回滚时定位下单标记
     */
    public void registerPending(Long orderId, Long voucherId, Long userId) {
        pending.put(orderId, new PendingInfo(voucherId, userId, System.currentTimeMillis()));
        int currentSize = pending.size();
        if (currentSize > PENDING_WARN_THRESHOLD && currentSize % 1000 == 0) {
            log.error("【MQ待确认积压告警】当前未收到 confirm 的秒杀消息已达 {} 条，请检查 RabbitMQ 确认性能", currentSize);
        }
    }

    /**
     * 撤销登记。
     *
     * <p>用于投递动作本身抛异常的场景：此时调用方已同步回滚，
     * 不能再等异步确认，否则会重复回滚。
     *
     * @param orderId 订单号
     */
    public void discardPending(Long orderId) {
        pending.remove(orderId);
    }

    /**
     * 投递确认回调：未确认或被退回时回滚 Redis 预扣。
     *
     * <p>先移除登记再判断结果，保证同一消息无论回调几次只回滚一次。
     *
     * @param correlationData 投递时携带的关联数据
     * @param ack             {@code true} 表示 broker 已接收
     * @param cause           失败原因（{@code ack} 为 {@code true} 时通常为 {@code null}）
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        Long orderId = parseOrderId(correlationData);
        if (orderId == null) {
            return;
        }
        PendingInfo info = pending.remove(orderId);
        if (info == null) {
            return;
        }
        if (!ack) {
            // broker 未收到消息（如交换机不存在、连接中断），必须回滚预扣
            log.error("秒杀订单消息投递未被 broker 确认，回滚 Redis 预扣，orderId={}, cause={}", orderId, cause);
            rollback(orderId, info);
            return;
        }
        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            // broker 收到但路由失败被退回（如队列/绑定不存在），同样需要回滚
            log.error("秒杀订单消息路由失败被退回，回滚 Redis 预扣，orderId={}, replyText={}",
                    orderId, returned.getReplyText());
            rollback(orderId, info);
        }
    }

    /**
     * 从 CorrelationData 中解析订单号。
     *
     * @param correlationData 投递关联数据，可能为 {@code null}
     * @return 订单号；无法解析时返回 {@code null}，调用方直接忽略本次回调
     */
    private Long parseOrderId(CorrelationData correlationData) {
        if (correlationData == null || correlationData.getId() == null) {
            return null;
        }
        try {
            return Long.parseLong(correlationData.getId());
        } catch (NumberFormatException e) {
            log.warn("无法解析秒杀订单消息的 CorrelationData id={}", correlationData.getId());
            return null;
        }
    }

    /**
     * 回滚 Redis 中预扣的库存与用户下单标记。
     *
     * <p>回滚脚本幂等，重复调用不会凭空增加库存。
     *
     * @param orderId 订单号，仅用于日志追踪
     * @param info    待确认信息，含回滚所需的 voucherId 与 userId
     */
    private void rollback(Long orderId, PendingInfo info) {
        try {
            stringRedisTemplate.execute(
                    ROLLBACK_SCRIPT,
                    Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + info.getVoucherId(),
                            RedisConstants.SECKILL_ORDER_KEY + info.getVoucherId()),
                    info.getUserId().toString(), "1"
            );
        } catch (Exception e) {
            log.error("秒杀 Redis 回滚失败，需人工介入：orderId={}, voucherId={}, userId={}",
                    orderId, info.getVoucherId(), info.getUserId(), e);
        }
    }

    /**
     * 清理超时未收到确认的登记，防止内存泄漏。
     *
     * <p>固定延迟 60 秒执行。超时的消息说明 broker 长时间未回调，
     * 可能是 broker 压力大导致 confirm 延迟、但消息其实已经入队并被成功消费。
     * 因此<b>必须先查库确认订单未入库</b>，才可回滚预扣；
     * 若订单已落库，说明只是回执延迟，绝对不能回滚（否则抹除成功订单标记产生幽灵复活）。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void cleanStalePending() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, PendingInfo> entry : pending.entrySet()) {
            PendingInfo info = entry.getValue();
            if (now - info.getCreateTime() > PENDING_TIMEOUT_MILLIS) {
                if (pending.remove(entry.getKey(), info)) {
                    Long orderId = entry.getKey();
                    // 关键兜底：先查 DB 确认该订单是否已经成功落库
                    if (voucherOrderMapper.selectById(orderId) != null) {
                        log.warn("秒杀订单确认回执超时，但数据库中订单已成功落库，跳过回滚，orderId={}", orderId);
                        continue;
                    }
                    log.error("秒杀订单消息长时间未收到确认且未入库，回滚 Redis 预扣，orderId={}", orderId);
                    rollback(orderId, info);
                }
            }
        }
    }

    /**
     * 待确认记录。
     */
    @Data
    private static class PendingInfo {

        /** 优惠券 id，回滚时定位库存 key */
        private final Long voucherId;

        /** 用户 id，回滚时定位下单标记 */
        private final Long userId;

        /** 登记时间戳（毫秒），用于判断是否超时 */
        private final long createTime;
    }
}