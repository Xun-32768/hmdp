package com.hmdp.service.impl;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.SeckillVoucher;
import com.hmdp.pojo.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_KEY_TTL_MINUTES;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_TIME_KEY;
import static com.hmdp.utils.SystemConstants.BIZ_ZONE_ID;
import static com.hmdp.utils.SystemConstants.bizNow;

/**
 * 优惠券服务实现。
 *
 * <p>除常规券查询外，还负责秒杀券的创建与 Redis 预热：
 * 活动创建成功后立即把库存与时间窗口写入 Redis，
 * 使抢单链路（{@code seckill.lua}）完全不需要访问数据库。
 *
 * @author axun
 * @since 2026-01-11
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 新增秒杀优惠券（券信息 + 秒杀信息 + Redis 预热）。
     *
     * <p>三步写操作在同一事务内完成，任一步失败整体回滚。
     * Redis 写入虽不受事务控制，但 key 带 TTL，且由定时对账兵底修正。
     *
     * @param voucher 优惠券信息，需携带 stock、beginTime、endTime
     * @throws IllegalArgumentException 库存为空或为负数、时间缺失、结束时间早于开始时间
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() < 0) {
            throw new IllegalArgumentException("秒杀券库存不能为空且不能为负数");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            throw new IllegalArgumentException("秒杀券必须指定开始与结束时间");
        }
        if (voucher.getEndTime().isBefore(voucher.getBeginTime())) {
            throw new IllegalArgumentException("秒杀券结束时间必须晚于开始时间");
        }
        // 秒杀券类型固定为 1
        voucher.setType((byte) 1);
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 预热库存与活动时间窗口到 Redis（事务提交后由 Lua 使用）
        cacheSeckillVoucher(voucher.getId(), voucher.getStock(),
                voucher.getBeginTime(), voucher.getEndTime());
    }

    /**
     * 把秒杀库存与活动时间窗口写入 Redis（供秒杀脚本读取）。
     *
     * <p>写入内容：
     * <ul>
     *   <li>{@code seckill:stock:{id}} —— 剩余库存</li>
     *   <li>{@code seckill:time:{id}} —— 时间窗口，格式 {@code beginMillis:endMillis}</li>
     *   <li>{@code seckill:order:{id}} —— 只续期不重建，保留已下单用户集合</li>
     * </ul>
     *
     * <p>时间字面量固定按业务时区解释，不依赖 JVM 默认时区，
     * 否则容器内为 UTC 时整个活动窗口会静默偏移 8 小时。
     *
     * @param voucherId 优惠券 id
     * @param stock     库存数量
     * @param beginTime 活动开始时间（无时区字面量）
     * @param endTime   活动结束时间（无时区字面量）
     */
    public void cacheSeckillVoucher(Long voucherId, Integer stock,
                                    LocalDateTime beginTime, LocalDateTime endTime) {
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String timeKey = SECKILL_TIME_KEY + voucherId;

        // key 至少保留到活动结束后一段时间，便于后续对账与用户查询
        Duration ttl = Duration.ofMinutes(SECKILL_KEY_TTL_MINUTES);
        if (endTime != null) {
            LocalDateTime expireAt = endTime.plusMinutes(SECKILL_KEY_TTL_MINUTES);
            Duration candidate = Duration.between(bizNow(), expireAt);
            if (candidate.isNegative()) {
                candidate = Duration.ofMinutes(1);
            }
            ttl = candidate;
        }

        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock), ttl);
        if (beginTime != null && endTime != null) {
            // 固定按业务时区解释数据库里的时间字面量，
            // 不依赖 JVM 默认时区（容器内常为 UTC，否则会整体偏移 8 小时）
            long beginMillis = beginTime.atZone(BIZ_ZONE_ID).toInstant().toEpochMilli();
            long endMillis = endTime.atZone(BIZ_ZONE_ID).toInstant().toEpochMilli();
            stringRedisTemplate.opsForValue().set(timeKey, beginMillis + ":" + endMillis, ttl);
        }
        // 下单用户集合同寿命，活动结束后自动释放，避免 Redis 无限增长
        stringRedisTemplate.expire(SECKILL_ORDER_KEY + voucherId, ttl);
    }

    /**
     * 查询指定店铺的优惠券列表。
     *
     * @param shopId 店铺 id
     * @return 该店铺下全部优惠券
     */
    public Result queryVoucherOfShop(Long shopId){
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

}
