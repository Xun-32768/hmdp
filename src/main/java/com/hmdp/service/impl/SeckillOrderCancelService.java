package com.hmdp.service.impl;

import com.hmdp.pojo.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀订单取消服务。
 *
 * <p>独立成 Bean 是为了让 {@link #cancelOrder} 的 {@code @Transactional} 真正生效：
 * 若写在定时任务类里被同类方法内部调用，Spring AOP 代理会被绕过，事务不生效。
 *
 * <p>同时负责 DB 与 Redis 两侧的库存归还，以及解除用户下单标记，
 * 三步缺一不可：只改 DB 会让 Redis 库存永久少一张，
 * 不解除标记则用户会被 Lua 的一人一单校验永久拦住。
 *
 * @author axun
 * @since 2026-02-01
 */
@Slf4j
@Service
public class SeckillOrderCancelService {

    /** 订单状态：未支付 */
    private static final byte ORDER_STATUS_UNPAID = 1;

    /** 订单状态：已取消 */
    private static final byte ORDER_STATUS_CANCELED = 4;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 取消订单并归还库存。
     *
     * <p><b>幂等</b>：先做「未支付 → 已取消」的条件更新，
     * 只有真正把状态改成功的那一次才会归还库存，
     * 因此重复调用（多实例定时任务、消息重投）不会凭空多出库存。
     *
     * @param order 待取消的订单，需携带 id / voucherId / userId
     * @return {@code true} 表示本次确实取消了订单；
     *         {@code false} 表示订单已被支付或已取消
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelOrder(VoucherOrder order) {
        // 1. 只更新仍未支付的订单，避免与用户支付并发覆盖
        boolean updated = voucherOrderService.update()
                .set("status", ORDER_STATUS_CANCELED)
                .eq("id", order.getId())
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!updated) {
            return false;
        }
        Long voucherId = order.getVoucherId();
        Long userId = order.getUserId();
        // 2. 归还数据库库存
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .update();
        // 3. 归还 Redis 库存并解除用户下单标记
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            stringRedisTemplate.opsForValue().increment(stockKey);
        }
        stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, userId.toString());
        log.info("超时订单已取消并归还库存，orderId={}, voucherId={}, userId={}",
                order.getId(), voucherId, userId);
        return true;
    }
}