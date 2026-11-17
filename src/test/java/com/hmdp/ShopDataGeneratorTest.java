package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.config.ShopEsInit;
import com.hmdp.pojo.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@SpringBootTest
public class ShopDataGeneratorTest {

    @Resource
    private IShopService shopService;

    @Resource
    private ShopEsInit shopEsInit;

    private static final String DEFAULT_IMG_1 = "https://p0.meituan.net/bbia/c1870d570e73accbc9fee90b48faca41195272.jpg";
    private static final String DEFAULT_IMG_2 = "https://qcloud.dpfile.com/pc/MZTdRDqCZdbPDUO0Hk6lZENRKzpKRF7kavrkEI99OxqBZTzPfIxa5E33gBfGouhFuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg";
    private static final String DEFAULT_IMG_3 = "https://p0.meituan.net/biztone/694233_1619500156517.jpeg";
    private static final String DEFAULT_IMG_4 = "https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg";

    @Test
    public void generateMockShops() {
        List<Shop> mockList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 1. 美食类 (typeId = 1)
        mockList.add(createShop("外婆家(西湖湖滨店)", 1L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_2, "湖滨商圈",
                "延安路258号湖滨银泰in77 C区4层", 120.163124, 30.254128, 65L, 3420, 2180, 48, "10:30-21:30", now));
        mockList.add(createShop("绿茶餐厅(龙井总店)", 1L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_3, "西湖风景区",
                "龙井路83号(近问茶楼)", 120.121542, 30.228965, 82L, 4120, 3190, 47, "10:30-21:00", now));
        mockList.add(createShop("知味观·味庄(杨公堤店)", 1L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_1, "西湖风景区",
                "杨公堤10-12号", 120.134581, 30.245892, 168L, 1980, 1540, 49, "11:00-20:30", now));
        mockList.add(createShop("湊湊火锅·茶憩(万象城店)", 1L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_2, "钱江新城",
                "富春路701号万象城B1层", 120.212543, 30.252431, 145L, 2890, 1870, 48, "11:00-02:00", now));
        mockList.add(createShop("陶陶居酒家(杭州嘉里中心店)", 1L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_4, "武林商圈",
                "延安路385号嘉里中心3层", 120.165412, 30.264512, 118L, 2150, 1420, 46, "10:00-21:30", now));
        mockList.add(createShop("哥老官重庆美蛙鱼头(西湖文化广场店)", 1L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_2, "武林商圈",
                "中山北路588号西湖文化广场B2层", 120.168745, 30.281245, 125L, 3100, 2450, 47, "10:30-02:00", now));
        mockList.add(createShop("杭儿风(城西银泰店)", 1L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_1, "城西银泰",
                "丰潭路380号城西银泰城4层", 120.106512, 30.301245, 78L, 1890, 1280, 45, "11:00-21:00", now));

        // 2. KTV (typeId = 2)
        mockList.add(createShop("纯K(钱江新城店)", 2L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_3, "钱江新城",
                "富春路万象城旁近江大厦3层", 120.208912, 30.248765, 88L, 4520, 2890, 48, "12:00-06:00", now));
        mockList.add(createShop("温莎KTV(西湖店)", 2L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_4, "湖滨商圈",
                "庆春路227号西湖时代广场4楼", 120.162451, 30.260142, 72L, 3810, 1950, 46, "11:00-06:00", now));
        mockList.add(createShop("银乐迪KTV(武林广场店)", 2L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_1, "武林商圈",
                "延安路530号银泰百货武林店A馆8层", 120.163892, 30.272145, 65L, 2900, 1680, 45, "10:00-04:00", now));

        // 3. 丽人·美发 (typeId = 3)
        mockList.add(createShop("苑苑造型(万象城旗舰店)", 3L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_1, "钱江新城",
                "富春路701号万象城4层", 120.213145, 30.252874, 280L, 1560, 1120, 49, "10:00-22:00", now));
        mockList.add(createShop("侨治发型(湖滨银泰in77店)", 3L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_2, "湖滨商圈",
                "延安路239号湖滨银泰D区3层", 120.163891, 30.253912, 220L, 2100, 1640, 48, "10:00-22:00", now));
        mockList.add(createShop("曼都发型(嘉里中心店)", 3L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_3, "武林商圈",
                "延安路385号嘉里中心北区B1层", 120.164812, 30.263871, 160L, 1870, 1340, 47, "10:00-21:30", now));
        mockList.add(createShop("杜尚造型(城西银泰店)", 3L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_4, "城西银泰",
                "丰潭路380号城西银泰城3层", 120.106892, 30.301542, 135L, 1230, 890, 46, "10:00-21:30", now));

        // 4. 健身运动 (typeId = 4)
        mockList.add(createShop("乐刻健身(运河上街24h店)", 4L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_4, "运河上街",
                "台州路2号运河上街购物中心B1层", 120.151812, 30.324812, 99L, 5600, 3120, 48, "00:00-24:00", now));
        mockList.add(createShop("超级猩猩健身(嘉里中心全能店)", 4L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_1, "武林商圈",
                "延安路385号嘉里中心3层", 120.165124, 30.264128, 89L, 4310, 2780, 49, "07:00-22:30", now));
        mockList.add(createShop("一兆韦德健身(万象城游泳健身店)", 4L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_2, "钱江新城",
                "富春路701号万象城5层", 120.212874, 30.252614, 350L, 1820, 1250, 47, "08:00-22:00", now));
        mockList.add(createShop("威尔仕健身(滨江星光大道店)", 4L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_3, "滨江商圈",
                "江南大道228号星光大道二期4层", 120.210452, 30.211425, 260L, 2140, 1430, 46, "07:30-22:00", now));

        // 5. 按摩·足疗 (typeId = 5)
        mockList.add(createShop("大浪淘沙养生水汇(黄龙店)", 5L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_2, "黄龙商圈",
                "杭大路15号黄龙饭店旁", 120.141254, 30.268741, 299L, 3120, 2190, 48, "00:00-24:00", now));
        mockList.add(createShop("泰禾古法泰式SPA按摩(湖滨店)", 5L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_3, "湖滨商圈",
                "东坡路66号湖滨名品街3楼", 120.161245, 30.256841, 238L, 2450, 1820, 49, "11:00-24:00", now));
        mockList.add(createShop("富侨足道(武林店)", 5L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_4, "武林商圈",
                "体育场路352号3层", 120.167812, 30.273145, 128L, 1980, 1340, 46, "11:00-02:00", now));
        mockList.add(createShop("汉尊养生会所(运河店)", 5L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_1, "运河上街",
                "金华路锦昌文华苑商铺18号", 120.149872, 30.316845, 158L, 1670, 1100, 47, "12:00-01:30", now));

        // 6. 美容SPA (typeId = 6)
        mockList.add(createShop("美丽田园(万象城店)", 6L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_3, "钱江新城",
                "富春路701号万象城4层", 120.213452, 30.253012, 480L, 1420, 980, 49, "10:00-22:00", now));
        mockList.add(createShop("思妍丽SPA(杭州大厦店)", 6L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_4, "武林商圈",
                "武林广场21号杭州大厦C座5层", 120.162874, 30.274125, 520L, 1290, 890, 48, "10:00-21:30", now));
        mockList.add(createShop("静心莲日式沉浸SPA(西湖店)", 6L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_1, "湖滨商圈",
                "南山路180号西湖艺术公馆2楼", 120.158741, 30.246812, 360L, 1870, 1310, 47, "10:00-22:30", now));

        // 7. 亲子游乐 (typeId = 7)
        mockList.add(createShop("奈尔宝家庭中心(滨江星光店)", 7L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_4, "滨江商圈",
                "江南大道228号星光大道一期1-2层", 120.209845, 30.212014, 298L, 4120, 2980, 49, "10:00-21:00", now));
        mockList.add(createShop("meland club儿童成长乐园(万象城店)", 7L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_3, "钱江新城",
                "富春路701号万象城3层", 120.212984, 30.252741, 268L, 3560, 2450, 48, "10:00-21:00", now));
        mockList.add(createShop("莫莉幻想(城西银泰店)", 7L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_1, "城西银泰",
                "丰潭路380号城西银泰城3层", 120.106412, 30.301389, 120L, 2890, 1780, 46, "09:30-21:30", now));

        // 8. 酒吧 (typeId = 8)
        mockList.add(createShop("MOVA Lounge 墨瓦高空江景酒吧", 8L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_2, "钱江新城",
                "市民街200号圣奥中央商务大厦39层", 120.211245, 30.246812, 198L, 2760, 1980, 49, "19:00-03:00", now));
        mockList.add(createShop("控制台BAR(武林夜市店)", 8L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_3, "武林商圈",
                "龙游路67号地下1层", 120.163451, 30.267812, 85L, 3420, 2340, 48, "18:30-02:30", now));
        mockList.add(createShop("晚安地球精酿啤酒吧(青芝坞店)", 8L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_1, "西湖风景区",
                "玉古路青芝坞152号", 120.128741, 30.261245, 95L, 2180, 1540, 47, "18:00-02:00", now));
        mockList.add(createShop("赫本酒吧HEPBURN(西湖文化广场店)", 8L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_4, "武林商圈",
                "西湖文化广场C区1-2层", 120.169124, 30.280845, 260L, 4890, 3120, 47, "20:30-05:00", now));

        // 9. 轰趴馆 (typeId = 9)
        mockList.add(createShop("九号星球剧本杀·轰趴馆(下沙大学城店)", 9L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_2, "下沙大学城",
                "学源街高沙商业街1幢3层", 120.328741, 30.309812, 88L, 3210, 2150, 48, "10:00-02:00", now));
        mockList.add(createShop("遇见森林独栋别墅轰趴(西溪湿地店)", 9L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_4, "西溪湿地",
                "文二西路西溪湿地北门慢生活街区8号别墅", 120.071245, 30.278912, 180L, 1980, 1420, 49, "00:00-24:00", now));
        mockList.add(createShop("奇幻岛桌游轰趴馆(滨江宝龙城店)", 9L, DEFAULT_IMG_3 + "," + DEFAULT_IMG_1, "滨江商圈",
                "滨盛路宝龙城商业中心4层", 120.187412, 30.198741, 75L, 2450, 1680, 46, "11:00-24:00", now));

        // 10. 美睫·美甲 (typeId = 10)
        mockList.add(createShop("Lily Nails日式美甲美睫(湖滨银泰店)", 10L, DEFAULT_IMG_4 + "," + DEFAULT_IMG_1, "湖滨商圈",
                "延安路258号湖滨银泰in77 B区3层", 120.163452, 30.254871, 158L, 2890, 1980, 49, "10:00-22:00", now));
        mockList.add(createShop("奈欧美甲沙龙(杭州大厦店)", 10L, DEFAULT_IMG_1 + "," + DEFAULT_IMG_3, "武林商圈",
                "武林广场21号杭州大厦D座3层", 120.163145, 30.273891, 198L, 1950, 1340, 48, "10:00-21:30", now));
        mockList.add(createShop("悦己日式美睫沙龙(嘉里中心店)", 10L, DEFAULT_IMG_2 + "," + DEFAULT_IMG_4, "武林商圈",
                "延安路385号嘉里中心北区2层", 120.165214, 30.264389, 138L, 2340, 1610, 47, "10:00-22:00", now));

        // 过滤掉已经存在的商铺（按名称匹配），避免重复插入
        List<Shop> toInsert = new ArrayList<>();
        for (Shop s : mockList) {
            long count = shopService.count(new LambdaQueryWrapper<Shop>().eq(Shop::getName, s.getName()));
            if (count == 0) {
                toInsert.add(s);
            }
        }

        if (!toInsert.isEmpty()) {
            boolean success = shopService.saveBatch(toInsert);
            Assertions.assertTrue(success, "批量插入商铺数据失败");
            System.out.println("成功向数据库插入商铺数量: " + toInsert.size());
        } else {
            System.out.println("数据已存在，跳过插入。");
        }

        // 触发全量同步到 Elasticsearch
        shopEsInit.run();
        System.out.println("Elasticsearch 同步完成，当前数据库商铺总数: " + shopService.count());
    }

    private Shop createShop(String name, Long typeId, String images, String area,
                            String address, Double x, Double y, Long avgPrice,
                            Integer sold, Integer comments, Integer score, String openHours, LocalDateTime now) {
        return new Shop()
                .setName(name)
                .setTypeId(typeId)
                .setImages(images)
                .setArea(area)
                .setAddress(address)
                .setX(x)
                .setY(y)
                .setAvgPrice(avgPrice)
                .setSold(sold)
                .setComments(comments)
                .setScore(score)
                .setOpenHours(openHours)
                .setCreateTime(now)
                .setUpdateTime(now);
    }
}