package com.hmdp.pojo.dto;

import lombok.Data;

@Data
public class ShopSearchDTO {
    /**
     * 搜索关键字（商户名称、地址）
     */
    private String name;

    /**
     * 商户分类ID
     */
    private Long typeId;

    /**
     * 商圈
     */
    private String area;

    /**
     * 最低人均消费
     */
    private Long minPrice;

    /**
     * 最高人均消费
     */
    private Long maxPrice;

    /**
     * 最低评分（实际是 10~50 对应 1~5 分）
     */
    private Integer minScore;

    /**
     * 当前用户经度
     */
    private Double x;

    /**
     * 当前用户纬度
     */
    private Double y;

    /**
     * 最大距离范围（单位：米，可选；不传则不限制距离）
     */
    private Double distance;

    /**
     * 排序规则:
     * default: 默认相关度排序
     * distance: 距离最近
     * sold: 销量最高
     * score: 评价最高
     * price_asc: 人均从低到高
     * price_desc: 人均从高到低
     */
    private String sortBy = "default";

    /**
     * 当前页
     */
    private Integer current = 1;

    /**
     * 每页数量
     */
    private Integer pageSize = 10;
}
