package com.hmdp.service;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-01-12
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}
