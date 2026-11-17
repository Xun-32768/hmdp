package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ShopSearchDTO;
import com.hmdp.pojo.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    Result saveShop(Shop shop);

    Result updateShop(Shop shop);

    Result searchShops(ShopSearchDTO params);

    Result getShopSuggestions(String prefix);
}
