package com.hmdp.controller;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  商铺类型控制类
 * </p>
 *
 * @author axun
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {

    @Resource
    private IShopTypeService shopTypeService;

    /**
     * 获取商铺类型列表
     * @return 商铺列表
     */
    @GetMapping("/list")
    public Result queryTypeList(){
        return shopTypeService.queryTypeList();
    }
}
