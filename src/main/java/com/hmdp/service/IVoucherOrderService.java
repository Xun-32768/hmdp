package com.hmdp.service;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 秒杀订单服务。
 *
 * <p>抢单走 {@link #seckillVoucher(Long)}（Redis + MQ，不访问 DB）；
 * 落库走 {@link #createVoucherOrder(VoucherOrder)}（由 MQ 消费者调用）。
 *
 * @author axun
 * @since 2026-01-12
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 抢单：Redis 原子预扣库存后投递消息，立即返回订单号。
     *
     * @param voucherId 优惠券 id
     * @return 成功时 data 为订单号；失败时带具体原因（库存不足 / 重复下单 / 活动未开始或已结束）
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 秒杀订单落库（由 MQ 消费者调用，需开启事务）。
     *
     * <p>返回 {@code true} 还包含「订单已存在（幂等命中）」的情形，
     * 因此消费端不能把 {@code true} 当做「本次新插了一条」来使用。
     *
     * @param voucherOrder 待落库订单
     * @return 业务落库结果：SUCCESS（成功或本订单已落库）、ALREADY_ORDERED（用户已有其他订单）、
     *         STOCK_INCONSISTENT（DB 库存不足，与 Redis 不一致）
     */
    OrderCommitOutcome createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 落库业务执行结果枚举。
     */
    enum OrderCommitOutcome {
        /** 本次落库成功，或本订单已存在（幂等命中） */
        SUCCESS,
        /** 该用户在该券下已存在其他订单（一人一单） */
        ALREADY_ORDERED,
        /** 数据库库存不足（与 Redis 预扣不一致） */
        STOCK_INCONSISTENT
    }
}
