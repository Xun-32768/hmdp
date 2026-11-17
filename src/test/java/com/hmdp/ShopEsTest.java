package com.hmdp;

import com.hmdp.config.ShopEsInit;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ShopDoc;
import com.hmdp.pojo.dto.ShopSearchDTO;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class ShopEsTest {

    @Resource
    private ShopEsInit shopEsInit;

    @Resource
    private IShopService shopService;

    @Test
    public void testSyncAndSearch() {
        // 1. 测试全量导入
        shopEsInit.run();

        // 2. 复合搜索测试（无关键字查第一页）
        ShopSearchDTO searchDTO = new ShopSearchDTO();
        searchDTO.setCurrent(1);
        searchDTO.setPageSize(5);
        Result result = shopService.searchShops(searchDTO);
        Assertions.assertTrue(result.getSuccess());
        List<?> records = (List<?>) result.getData();
        System.out.println("查询返回店铺数量: " + records.size());
        Assertions.assertFalse(records.isEmpty());

        // 3. 测试自动补全
        Result suggestResult = shopService.getShopSuggestions("大");
        System.out.println("补全结果: " + suggestResult.getData());
        Assertions.assertTrue(suggestResult.getSuccess());
        List<?> suggestions = (List<?>) suggestResult.getData();
        Assertions.assertFalse(suggestions.isEmpty());
    }

    @Test
    public void testQueryRestaurantsCompleteness() {
        // 1. 测试按美食分类 typeId=1 查询餐厅
        ShopSearchDTO typeQuery = new ShopSearchDTO();
        typeQuery.setTypeId(1L);
        typeQuery.setPageSize(20);
        Result typeRes = shopService.searchShops(typeQuery);
        Assertions.assertTrue(typeRes.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDoc> restaurantList = (List<ShopDoc>) typeRes.getData();
        System.out.println("==== 美食分类(typeId=1)商户列表 (共 " + restaurantList.size() + " 家) ====");
        for (ShopDoc s : restaurantList) {
            System.out.println(String.format("id=%d, name=%s, score=%d, avgPrice=%d, location=%s",
                    s.getId(), s.getName(), s.getScore(), s.getAvgPrice(), s.getLocation()));
        }
        Assertions.assertFalse(restaurantList.isEmpty(), "餐厅数据不应为空");

        // 2. 测试输入关键字 '餐厅' 搜索
        ShopSearchDTO nameQuery = new ShopSearchDTO();
        nameQuery.setName("餐厅");
        Result nameRes = shopService.searchShops(nameQuery);
        Assertions.assertTrue(nameRes.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDoc> nameMatches = (List<ShopDoc>) nameRes.getData();
        System.out.println("==== 关键字'餐厅'检索结果 (命中 " + nameMatches.size() + " 家) ====");
        for (ShopDoc s : nameMatches) {
            System.out.println("命中商户: " + s.getName());
            Assertions.assertTrue(s.getName().contains("<font color='red'>"), "关键字命中必须包含高亮标签");
        }
        Assertions.assertTrue(nameMatches.size() >= 2);

        // 2.1 测试通过中文类型名称进行搜索（如：'酒吧'、'美食'、'健身运动'）
        ShopSearchDTO typeNameQuery = new ShopSearchDTO();
        typeNameQuery.setName("酒吧");
        Result typeNameRes = shopService.searchShops(typeNameQuery);
        Assertions.assertTrue(typeNameRes.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDoc> barMatches = (List<ShopDoc>) typeNameRes.getData();
        System.out.println("==== 中文类型名'酒吧'检索结果 (命中 " + barMatches.size() + " 家) ====");
        Assertions.assertFalse(barMatches.isEmpty(), "搜索中文分类名'酒吧'应能检索出相关商户");
        for (ShopDoc s : barMatches) {
            System.out.println("酒吧商户: " + s.getName() + " | 分类: " + s.getTypeName());
        }

        // 3. 测试距离优先排序（带前端 mock 坐标）
        ShopSearchDTO distanceQuery = new ShopSearchDTO();
        distanceQuery.setTypeId(1L);
        distanceQuery.setSortBy("distance");
        distanceQuery.setX(120.149993);
        distanceQuery.setY(30.334229);
        Result distRes = shopService.searchShops(distanceQuery);
        Assertions.assertTrue(distRes.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopDoc> distList = (List<ShopDoc>) distRes.getData();
        System.out.println("==== 距离优先排序结果 ====");
        double lastDist = -1.0;
        for (ShopDoc s : distList) {
            System.out.println(String.format("%s -> 距离: %.1fm", s.getName(), s.getDistance()));
            Assertions.assertNotNull(s.getDistance());
            Assertions.assertTrue(s.getDistance() >= lastDist, "距离必须按升序排列");
            lastDist = s.getDistance();
        }

        // 4. 测试 '餐' 关键字的自动补全联想词
        Result suggestRes = shopService.getShopSuggestions("餐");
        Assertions.assertTrue(suggestRes.getSuccess());
        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) suggestRes.getData();
        System.out.println("==== '餐' 搜索自动建议词 ====");
        for (String item : suggestions) {
            System.out.println("建议词: " + item);
        }
        Assertions.assertFalse(suggestions.isEmpty(), "'餐' 应该能匹配出包含餐厅的建议词");
    }
}
