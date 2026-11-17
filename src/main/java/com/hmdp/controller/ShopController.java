package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ShopSearchDTO;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
@RestController
@RequestMapping("/shop")
public class ShopController {


    @Resource
    private IShopService shopService;

    /**
     * 商户多维度搜索与复合筛选（Elasticsearch 驱动）
     * 支持关键字搜索、分类过滤、商圈过滤、价格区间、评分筛选、距离过滤、多字段排序
     * @param params 复合筛选参数
     * @return 命中的商户列表及分页信息
     */
    @GetMapping("/search")
    public Result searchShops(ShopSearchDTO params) {
        return shopService.searchShops(params);
    }

    /**
     * 商铺搜索关键字自动补全建议词
     * @param key 前缀关键字
     * @return 建议词列表
     */
    @GetMapping("/suggestion")
    public Result getShopSuggestions(@RequestParam("key") String key) {
        return shopService.getShopSuggestions(key);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        return shopService.saveShop(shop);
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.updateShop(shop);
    }
    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x",required = false) Double x,
            @RequestParam(value = "y",required = false) Double y
    ) {
        return shopService.queryShopByType(typeId,current,x,y);
    }
    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return shopService.queryById(id);
    }
}
