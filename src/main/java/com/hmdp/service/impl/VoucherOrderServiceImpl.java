package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_TIME_KEY;
import static com.hmdp.utils.SystemConstants.bizNow;

/**
 * 秒杀订单服务实现。
 *
 * <p><b>抢单阶段（同步）</b>：{@link #seckillVoucher(Long)} 只执行一段 Lua 脚本，
 * 在 Redis 侧原子完成「活动时间窗口 + 库存 + 一人一单」三项校验并预扣库存，
 * 不访问数据库；成功后把订单投递到 MQ，立即返回订单号。
 *
 * <p><b>落库阶段（异步）</b>：{@link #listenSeckillOrder} 消费消息，加分布式锁后
 * 通过 {@link #createVoucherOrder} 落库，并按消费结果决定 ACK / 重试 / 死信。
 *
 * <p><b>失败补偿</b>：仅在 MQ 投递同步失败时才回滚 Redis 预扣；
 * 消费阶段用户已有其他订单时不回滚（避免误删成功订单标记产生「幽灵复活」），
 * DB 库存不足时转死信告警并由定时对账修正（避免盲目回补扩大与 DB 偏离）。
 *
 * @author axun
 * @since 2026-01-12
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** 秒杀主脚本：时间窗口 + 库存 + 一人一单校验并预扣库存（见 seckill.lua） */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    /** 回滚脚本：MQ 投递失败或确定性业务失败时，回滚库存与用户下单标记（见 seckill_rollback.lua） */
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    /** 下单锁等待时长（秒）：一次落库事务通常几十毫秒，3 秒足够等待前一个处理完成 */
    private static final long LOCK_WAIT_SECONDS = 3L;

    /** 下单锁持有时长（秒）：远大于正常事务耗时，显式指定以免看门狗无限续期 */
    private static final long LOCK_LEASE_SECONDS = 30L;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SeckillMqConfirmHandler mqConfirmHandler;

    /** 注入自身代理，保证 createVoucherOrder 上的 @Transactional 生效 */
    @Resource
    @Lazy
    private IVoucherOrderService proxy;

    /**
     * 秒杀下单（抢单入口）。
     *
     * <p>整个方法分为两段：先在 Redis 侧原子校验并预扣，再把订单投递到 MQ 异步落库。
     * 抢单链路不访问数据库，因此耗时稳定在「一次 Redis 往返 + 一次 MQ 投递」。
     *
     * @param voucherId 秒杀优惠券 id
     * @return 成功时返回订单号；失败时返回具体原因（库存不足 / 重复下单 / 未开始 / 已结束）
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long userId = currentUser.getId();

        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        String timeKey = SECKILL_TIME_KEY + voucherId;

        Long result;
        try {
            // 库存、一人一单、活动时间窗口全部在 Redis 侧原子校验，抢单链路不查数据库。
            // 脚本只需 userId：voucherId 已包含在 KEYS 中，时间取 Redis 服务端时钟，订单号不参与校验。
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Arrays.asList(stockKey, orderKey, timeKey),
                    userId.toString()
            );
        } catch (Exception e) {
            log.error("秒杀 Lua 脚本执行异常，voucherId={}, userId={}", voucherId, userId, e);
            return Result.fail("秒杀失败，请稍后重试");
        }
        if (result == null) {
            log.error("秒杀 Lua 脚本返回空，voucherId={}, userId={}", voucherId, userId);
            return Result.fail("秒杀失败，请稍后重试");
        }
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(switch (r) {
                case 1 -> "库存不足";
                case 2 -> "不能重复下单";
                case 3 -> "秒杀还未开始";
                case 4 -> "秒杀已经结束";
                default -> "秒杀活动不存在或已结束";
            });
        }
        // 预扣成功后才生成订单号：被拒绝的请求不再白耗 Redis 序列号
        long orderId = redisIdWorker.nextId("order");

        // 订单对象同时作为 MQ 消息体，落库所需字段在此一次性准备好
        VoucherOrder voucherOrder = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId)
                .setCreateTime(bizNow());
        // 先登记待确认，再投递：若 broker 确认失败/路由失败，由 confirm 回调回滚预扣
        mqConfirmHandler.registerPending(orderId, voucherId, userId);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SECKILL_EXCHANGE,
                    RabbitMQConfig.SECKILL_ROUTING_KEY,
                    voucherOrder,
                    new CorrelationData(String.valueOf(orderId))
            );
        } catch (Exception e) {
            // 投递动作本身失败，立即同步回滚，无需等待异步确认
            mqConfirmHandler.discardPending(orderId);
            log.error("秒杀订单投递 MQ 失败，回滚 Redis 预扣，orderId={}", orderId, e);
            rollbackRedis(voucherId, userId);
            return Result.fail("秒杀失败，请稍后重试");
        }
        return Result.ok(orderId);
    }

    /**
     * 回滚 Redis 中预扣的库存与用户下单标记。
     *
     * <p>回滚脚本幂等：只有 {@code SREM} 确实移除了该用户时才回补库存，
     * 因此重复调用（confirm 回调 + 同步异常、重试等）不会凭空增加库存。
     *
     * <p><b>注意：此方法仅供抢单阶段 MQ 投递同步异常时调用。</b>
     * 消费阶段落库失败时绝对不能调用此方法：
     * <ul>
     *   <li>用户已有其他订单时，Redis 里的标记属于那一笔成功订单，回滚会抹除标记导致一人一单失效；</li>
     *   <li>DB 库存不足时，回滚会把 Redis 库存越补越高，扩大与 DB 的不一致。</li>
     * </ul>
     *
     * @param voucherId 优惠券 id
     * @param userId    用户 id
     */
    private void rollbackRedis(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                    userId.toString(), "1"
            );
        } catch (Exception ex) {
            log.error("秒杀 Redis 回滚失败，需人工介入：voucherId={}, userId={}", voucherId, userId, ex);
        }
    }

    /**
     * 秒杀订单落库（事务方法，必须经代理调用才会生效）。
     *
     * <p>三道防线依次为：订单幂等判重 → 一人一单判重 → 条件扣减库存。
     * 其中库存扣减用 {@code UPDATE ... WHERE stock > 0} 完成，由 InnoDB 行锁保证同券并发安全。
     *
     * <p>事务超时显式设为 15 秒（远小于 Redisson 锁租约 30 秒），
     * 保证即便遇到慢查询或行锁等待，事务也必然在锁过期前滚回，杜绝锁静默失效后的并发破坏。
     *
     * @param voucherOrder 待落库订单
     * @return 业务落库结果 {@link OrderCommitOutcome}
     */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 15)
    public OrderCommitOutcome createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Long orderId = voucherOrder.getId();

        // ① 幂等判重：若订单已入库（MQ 重投），直接返回成功；外层 basicAck 该消息避免堆积
        if (getById(orderId) != null) {
            log.info("秒杀订单已存在，幂等命中，orderId={}", orderId);
            return OrderCommitOutcome.SUCCESS;
        }

        // ② 一人一单：上面已排除「本订单」，走到这里说明 DB 里是另一笔订单，属真重复
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.warn("用户在该券下已存在其他订单，userId={}, voucherId={}", userId, voucherId);
            return OrderCommitOutcome.ALREADY_ORDERED;
        }

        // ③ 扣减库存（where stock > 0，InnoDB 行锁天然串行化同券竞争，不靠分布式锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            log.warn("数据库库存不足，扣减失败，voucherId={}", voucherId);
            return OrderCommitOutcome.STOCK_INCONSISTENT;
        }

        // ④ 落库。并发下可能被主键/唯一索引拦下，抛出 DuplicateKeyException 让事务回滚
        //   （必须回滚，否则本次已扣的库存会白扣），由调用方捕获后当作幂等成功处理
        save(voucherOrder);
        return OrderCommitOutcome.SUCCESS;
    }

    /**
     * 加锁后落库，并把结果映射为 ACK 决策依据。
     *
     * <p>锁粒度是「用户 + 券」，只保证同一业务键的落库<b>不时间重叠</b>；
     * 重复投递若在锁释放之后才到达，锁拦不住，靠 {@link #createVoucherOrder} 里的订单判重兜底。
     *
     * @param voucherOrder 待落库订单
     * @return 消费结果，用于决定 ACK / 重试 / 死信
     */
    private CommitResult handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 锁粒度「用户 + 券」，同一用户抢不同券互不阻塞
        RLock redisLock = redissonClient.getLock("lock:order:" + userId + ":" + voucherId);
        boolean isLock;
        try {
            // 显式指定 leaseTime：避免消费者异常时看门狗无限续期导致锁永久不释放
            isLock = redisLock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取秒杀下单锁被中断，userId={}, voucherId={}", userId, voucherId, e);
            return CommitResult.LOCK_BUSY;
        }
        if (!isLock) {
            // 锁竞争说明同业务键正在被处理，本消息既没失败也不能回滚，交给延迟重试
            log.warn("获取下单锁超时，userId={}, voucherId={}", userId, voucherId);
            return CommitResult.LOCK_BUSY;
        }
        try {
            // 通过代理调用，保证 @Transactional 生效（跨线程事务需重新开启）
            OrderCommitOutcome outcome = proxy.createVoucherOrder(voucherOrder);
            return switch (outcome) {
                case SUCCESS -> CommitResult.SUCCESS;
                case ALREADY_ORDERED -> CommitResult.ALREADY_ORDERED;
                case STOCK_INCONSISTENT -> CommitResult.STOCK_INCONSISTENT;
            };
        } catch (DuplicateKeyException e) {
            // 主键或（user_id, voucher_id）唯一索引冲突：订单已存在，属幂等成功。
            // 事务已回滚，本次扣减的库存也随之回退，不会多扣
            log.info("秒杀订单唯一键冲突，视为幂等成功，orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), userId, voucherId);
            return CommitResult.SUCCESS;
        } finally {
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    /**
     * 消费结果，用于决定 ACK 策略。
     */
    private enum CommitResult {

        /** 落库成功或幂等命中：确认消息，不回滚 */
        SUCCESS,

        /** 用户已存在其他订单：已落库订单的标记属于有效状态，<b>绝对不能回滚 Redis</b>，确认消息并记录警告 */
        ALREADY_ORDERED,

        /** DB 库存不足（与 Redis 预扣不一致）：<b>不回滚 Redis（避免偏离扩大）</b>，转入死信队列待人工与对账任务处理 */
        STOCK_INCONSISTENT,

        /** 锁竞争（同业务键正在处理）：延迟重试，<b>不回滚</b> */
        LOCK_BUSY,

        /** 临时性异常（DB 抖动、网络等）：延迟重试，<b>不回滚</b> */
        RETRY
    }

    /**
     * 秒杀订单消费入口（手动 ACK）。
     *
     * <p>按 {@link CommitResult} 分派处理：
     * <ul>
     *   <li>{@code SUCCESS} —— ACK，预扣与落库均已完成</li>
     *   <li>{@code ALREADY_ORDERED} —— ACK，同一用户已有其他订单，不重复扣库存，亦不回滚 Redis 标记（防幽灵复活）</li>
     *   <li>{@code STOCK_INCONSISTENT} —— 转入死信队列告警，不回滚 Redis，等待定时对账修复</li>
     *   <li>{@code LOCK_BUSY} / {@code RETRY} —— 投递延迟重试队列后 ACK；超限转死信，全程不回滚</li>
     * </ul>
     *
     * @param voucherOrder 订单消息体
     * @param channel      AMQP 信道，用于手工 ACK / NACK
     * @param deliveryTag  投递标签，ACK / NACK 的定位依据
     * @param retryCount   已重试次数（来自消息头，首次消费为 {@code null}）
     * @throws IOException ACK / NACK 失败时抛出
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE)
    public void listenSeckillOrder(@Payload VoucherOrder voucherOrder,
                                   Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                   @Header(value = RabbitMQConfig.HEADER_RETRY_COUNT, required = false) Integer retryCount)
            throws IOException {
        int retried = retryCount == null ? 0 : retryCount;
        log.info("接收到秒杀订单消息，准备入库：订单号={}, 已重试={}", voucherOrder.getId(), retried);
        CommitResult result;
        try {
            result = handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            // 未知异常（DB 连接抖动、序列化问题等）无法判定预扣是否还有效，
            // 按临时失败处理：延迟重试且不回滚，绝不因一次异常就放弃用户的预扣
            log.error("处理秒杀订单异常，转入延迟重试，订单号={}", voucherOrder.getId(), e);
            result = CommitResult.RETRY;
        }
        switch (result) {
            // 落库成功或幂等命中：ACK 确认当前消息，完成生命周期闭环
            case SUCCESS -> channel.basicAck(deliveryTag, false);
            case ALREADY_ORDERED -> {
                // 用户在该券下已有其他订单。Redis 里的下单标记属于那一笔成功订单，
                // 直接 ACK 消费结束即可。
                log.warn("用户已存在其他秒杀订单，丢弃重复消息且不回滚预扣，订单号={}, userId={}, voucherId={}",
                        voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
                channel.basicAck(deliveryTag, false);
            }
            case STOCK_INCONSISTENT -> {
                // 数据库库存不足（与 Redis 预扣不一致）。
                // 直接投递死信队列记录审计，等待定时对账任务 reconcileStock 修正 Redis 库存。
                log.error("数据库库存不足与 Redis 预扣不一致，转入死信队列待对账，订单号={}, voucherId={}",
                        voucherOrder.getId(), voucherOrder.getVoucherId());
                if (publishToDlq(voucherOrder, "DB库存不足与Redis不一致")) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    log.error("死信投递失败，保留未确认消息等待重投，订单号={}", voucherOrder.getId());
                }
            }
            case LOCK_BUSY, RETRY -> {
                // 临时性失败：Redis 预扣依然有效，消息不能丢，走延迟重试队列稍后再试
                if (retried < RabbitMQConfig.SECKILL_MAX_RETRY) {
                    if (publishToRetryQueue(voucherOrder, retried + 1)) {
                        channel.basicAck(deliveryTag, false);
                    } else {
                        // 重投失败时退回队列，至少保证消息还在 broker 里
                        channel.basicNack(deliveryTag, false, true);
                    }
                } else {
                    // 重试超限：消息转入死信等人工核对。不回滚Redis信息
                    log.error("秒杀订单重试次数超限，转入死信队列待人工核对，订单号={}, 原因={}",
                            voucherOrder.getId(), result);
                    if (publishToDlq(voucherOrder, result == CommitResult.LOCK_BUSY
                            ? "锁竞争重试次数超限" : "临时异常重试次数超限")) {
                        channel.basicAck(deliveryTag, false);
                    } else {
                        // 死信投递也失败：不 ACK，让消息保持未确认状态，连接恢复后重回队列
                        log.error("死信投递失败，保留未确认消息等待重投，订单号={}", voucherOrder.getId());
                    }
                }
            }
        }
    }

    /**
     * 投递到延迟重试队列。
     *
     * <p>该队列只做 TTL 暂存，消息过期后由死信路由自动回到业务队列，实现延迟重试。
     *
     * @param voucherOrder   订单消息体
     * @param nextRetryCount 本次重试序号，写入消息头供下次消费读取
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToRetryQueue(VoucherOrder voucherOrder, int nextRetryCount) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SECKILL_RETRY_EXCHANGE,
                    RabbitMQConfig.SECKILL_RETRY_ROUTING_KEY,
                    voucherOrder,
                    message -> {
                        message.getMessageProperties().setHeader(RabbitMQConfig.HEADER_RETRY_COUNT, nextRetryCount);
                        return message;
                    }
            );
            log.warn("秒杀订单转入延迟重试，订单号={}, 第 {} 次重试", voucherOrder.getId(), nextRetryCount);
            return true;
        } catch (Exception e) {
            log.error("秒杀订单投递重试队列失败，订单号={}", voucherOrder.getId(), e);
            return false;
        }
    }

    /**
     * 手工投递到死信交换机。
     *
     * <p>用于无法通过 NACK 自动死信的场景（如需要附加失败原因），
     * 消息会被 {@code SeckillDlqConsumer} 记录审计，等待人工核对补单。
     *
     * @param voucherOrder 订单消息体
     * @param reason       进入死信的原因，写入消息头便于排查
     * @return {@code true} 表示投递动作成功发出
     */
    private boolean publishToDlq(VoucherOrder voucherOrder, String reason) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.SECKILL_DLX,
                    RabbitMQConfig.SECKILL_DLQ_ROUTING_KEY,
                    voucherOrder,
                    message -> {
                        message.getMessageProperties().setHeader(RabbitMQConfig.HEADER_FAIL_REASON, reason);
                        return message;
                    }
            );
            return true;
        } catch (Exception e) {
            // 死信也发不出去时只能落日志，靠 orderId 人工追溯
            log.error("秒杀订单投递死信队列失败，需人工介入，订单号={}, voucherId={}, userId={}, reason={}",
                    voucherOrder.getId(), voucherOrder.getVoucherId(), voucherOrder.getUserId(), reason, e);
            return false;
        }
    }
}
