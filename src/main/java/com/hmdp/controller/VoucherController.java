package com.hmdp.controller;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Voucher;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠券控制类
 * @author axun
 * @since 2026-01-11
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀卷
     * @param voucher
     * @return
     */
    @PostMapping("/seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher){
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }
}
