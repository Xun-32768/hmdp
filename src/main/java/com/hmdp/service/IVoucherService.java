package com.hmdp.service;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-01-11
 */
public interface IVoucherService extends IService<Voucher> {

    void addSeckillVoucher(Voucher voucher);

    /**
     * 把秒杀库存与活动时间窗口写入 Redis（预热/对账时使用）
     */
    void cacheSeckillVoucher(Long voucherId, Integer stock,
                             java.time.LocalDateTime beginTime, java.time.LocalDateTime endTime);

    Result queryVoucherOfShop(Long shopId);
}
