package com.hmdp.service.impl;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-01-12
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

   public Result queryTypeList(){
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        return Result.ok(shopTypeList);
   }
}
