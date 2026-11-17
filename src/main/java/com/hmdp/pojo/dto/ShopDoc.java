package com.hmdp.pojo.dto;

import com.hmdp.pojo.entity.Shop;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
public class ShopDoc {
    private Long id;
    private String name;
    private Long typeId;
    /**
     * 分类中文名称（如：美食、KTV、酒吧等）
     */
    private String typeName;
    private String images;
    private String area;
    private String address;
    private Double x;
    private Double y;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;
    /**
     * geo_point 格式: "lat,lon" 对应 "y,x"
     */
    private String location;
    /**
     * 自动补全/建议词列表（店名 + 商圈）
     */
    private List<String> suggestion;
    /**
     * 距离（非持久化存储，搜索时动态回填，单位：米）
     */
    private Double distance;

    public ShopDoc(Shop shop) {
        this(shop, null);
    }

    public ShopDoc(Shop shop, String typeName) {
        this.id = shop.getId();
        this.name = shop.getName();
        this.typeId = shop.getTypeId();
        this.typeName = typeName;
        this.images = shop.getImages();
        this.area = shop.getArea();
        this.address = shop.getAddress();
        this.x = shop.getX();
        this.y = shop.getY();
        this.avgPrice = shop.getAvgPrice();
        this.sold = shop.getSold();
        this.comments = shop.getComments();
        this.score = shop.getScore();
        this.openHours = shop.getOpenHours();
        this.distance = shop.getDistance();

        // 经纬度组合: lat(y),lon(x)
        if (shop.getY() != null && shop.getX() != null) {
            this.location = shop.getY() + "," + shop.getX();
        }

        // 补全建议词（店名 + 商圈 + 分类名）
        this.suggestion = new ArrayList<>();
        if (shop.getName() != null) {
            this.suggestion.add(shop.getName());
        }
        if (shop.getArea() != null) {
            this.suggestion.addAll(Arrays.asList(shop.getArea().split("、|,|/")));
        }
        if (typeName != null && !typeName.isBlank()) {
            this.suggestion.add(typeName);
        }
    }
}
