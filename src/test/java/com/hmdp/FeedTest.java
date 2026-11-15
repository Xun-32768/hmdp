package com.hmdp;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ScrollResult;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FeedTest {

    @Resource
    private IBlogService blogService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private static final Long FAN_USER_ID = 9991L;
    private static final Long SMALL_V_USER_ID = 9992L;
    private static final Long BIG_V_USER_ID = 9993L;

    private Long smallVBlogId;
    private Long bigVBlogId;

    private UserDTO createUser(Long id, String nickName) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setNickName(nickName);
        return user;
    }

    @Test
    @Order(1)
    @DisplayName("1. 环境清理与基础测试数据准备")
    void testInitData() {
        System.out.println("========== 1. 初始化测试数据 ==========");
        // 1.1 清理历史测试数据
        cleanAllTestData();

        // 1.2 插入用户基本信息 (tb_user)
        jdbcTemplate.update("INSERT INTO tb_user (id, phone, nick_name, icon) VALUES (?, ?, ?, ?)",
                FAN_USER_ID, "13800009991", "测试粉丝-小明", "");
        jdbcTemplate.update("INSERT INTO tb_user (id, phone, nick_name, icon) VALUES (?, ?, ?, ?)",
                SMALL_V_USER_ID, "13800009992", "测试小V-小红", "");
        jdbcTemplate.update("INSERT INTO tb_user (id, phone, nick_name, icon) VALUES (?, ?, ?, ?)",
                BIG_V_USER_ID, "13800009993", "测试大V-大强", "");

        // 1.3 插入用户详情信息 (tb_user_info)
        // 9991: 普通粉丝，role_type = 0, fans = 0
        jdbcTemplate.update("INSERT INTO tb_user_info (user_id, fans, followee, role_type) VALUES (?, ?, ?, ?)",
                FAN_USER_ID, 0, 0, 0);
        // 9992: 小V，role_type = 0, fans = 10 (< 3500)
        jdbcTemplate.update("INSERT INTO tb_user_info (user_id, fans, followee, role_type) VALUES (?, ?, ?, ?)",
                SMALL_V_USER_ID, 10, 0, 0);
        // 9993: 大V，role_type = 1, fans = 5000 (>= 3500)
        jdbcTemplate.update("INSERT INTO tb_user_info (user_id, fans, followee, role_type) VALUES (?, ?, ?, ?)",
                BIG_V_USER_ID, 5000, 0, 1);

        System.out.println("测试用户与角色信息初始化成功！");
    }

    @Test
    @Order(2)
    @DisplayName("2. 关注功能验证（区分大V小V缓存）")
    void testFollow() {
        System.out.println("========== 2. 测试粉丝关注大V与小V ==========");
        // 模拟粉丝 9991 登录
        UserHolder.saveUser(createUser(FAN_USER_ID, "测试粉丝-小明"));

        // 2.1 关注小V (9992)
        Result r1 = followService.follow(SMALL_V_USER_ID, true);
        assertTrue(r1.getSuccess(), "关注小V应该成功");

        // 验证小V不属于大V集合
        Boolean isSmallVInFamous = stringRedisTemplate.opsForSet().isMember(
                RedisConstants.FAMOUS_FOLLOW_KEY + FAN_USER_ID,
                SMALL_V_USER_ID.toString()
        );
        assertFalse(Boolean.TRUE.equals(isSmallVInFamous), "小V不能存在于 famousFollows 集合中");

        // 验证小V的粉丝集合记录了粉丝 9991
        Boolean isFanRecorded = stringRedisTemplate.opsForSet().isMember(
                RedisConstants.FANS_KEY + SMALL_V_USER_ID,
                FAN_USER_ID.toString()
        );
        assertTrue(Boolean.TRUE.equals(isFanRecorded), "小V的粉丝集合中必须包含该粉丝");

        // 2.2 关注大V (9993)
        Result r2 = followService.follow(BIG_V_USER_ID, true);
        assertTrue(r2.getSuccess(), "关注大V应该成功");

        // 验证大V被加入到粉丝的 famousFollows 集合
        Boolean isBigVInFamous = stringRedisTemplate.opsForSet().isMember(
                RedisConstants.FAMOUS_FOLLOW_KEY + FAN_USER_ID,
                BIG_V_USER_ID.toString()
        );
        assertTrue(Boolean.TRUE.equals(isBigVInFamous), "大V必须记录在粉丝的 famousFollows 集合中");

        // 验证大V粉丝集合不保存粉丝（避免大V百万粉丝撑爆Redis）
        Boolean bigVFansHasKey = stringRedisTemplate.hasKey(RedisConstants.FANS_KEY + BIG_V_USER_ID);
        assertFalse(Boolean.TRUE.equals(bigVFansHasKey), "大V不应该维护 fans 粉丝集合");

        System.out.println("关注功能与大V/小V缓存隔离验证通过！");
    }

    @Test
    @Order(3)
    @DisplayName("3. 发布笔记测试（小V写扩散 vs 大V发件箱）")
    void testPublishBlogs() throws InterruptedException {
        System.out.println("========== 3. 测试小V与大V发布笔记 ==========");

        // 3.1 小V (9992) 发布笔记
        UserHolder.saveUser(createUser(SMALL_V_USER_ID, "测试小V-小红"));
        Blog blogSmall = new Blog();
        blogSmall.setShopId(1L);
        blogSmall.setTitle("小V的探店日记");
        blogSmall.setContent("今天天气真好，小V来吃火锅啦！");
        blogSmall.setImages("/imgs/blogs/smallv.jpg");
        Result resSmall = blogService.saveBlog(blogSmall);
        assertTrue(resSmall.getSuccess(), "小V发布笔记成功");
        smallVBlogId = (Long) resSmall.getData();
        assertNotNull(smallVBlogId);

        // 验证小V彻底不写自己的发件箱
        Boolean smallVHasOutbox = stringRedisTemplate.hasKey(RedisConstants.BLOG_OUTBOX_KEY + SMALL_V_USER_ID);
        assertFalse(Boolean.TRUE.equals(smallVHasOutbox), "小V不应该占用 Redis 写入发件箱");

        // 模拟等待 RabbitMQ 异步写扩散消费完成 (1.5秒)
        System.out.println("等待 RabbitMQ 异步将小V笔记写扩散推送到粉丝收件箱...");
        Thread.sleep(1500);

        // 验证粉丝的收件箱 feed:9991 中已经收到了小V的笔记
        Double smallBlogScore = stringRedisTemplate.opsForZSet().score(
                RedisConstants.FEED_KEY + FAN_USER_ID,
                smallVBlogId.toString()
        );
        assertNotNull(smallBlogScore, "粉丝收件箱中必须成功接收到小V写扩散推送的笔记");

        // 3.2 大V (9993) 发布笔记
        UserHolder.saveUser(createUser(BIG_V_USER_ID, "测试大V-大强"));
        Blog blogBig = new Blog();
        blogBig.setShopId(1L);
        blogBig.setTitle("大V百万级推荐日记");
        blogBig.setContent("顶级米其林体验，值得打卡！");
        blogBig.setImages("/imgs/blogs/bigv.jpg");
        Result resBig = blogService.saveBlog(blogBig);
        assertTrue(resBig.getSuccess(), "大V发布笔记成功");
        bigVBlogId = (Long) resBig.getData();
        assertNotNull(bigVBlogId);

        // 验证大V写入了发件箱 blog:outbox:9993
        Double bigBlogScore = stringRedisTemplate.opsForZSet().score(
                RedisConstants.BLOG_OUTBOX_KEY + BIG_V_USER_ID,
                bigVBlogId.toString()
        );
        assertNotNull(bigBlogScore, "大V笔记必须写入自己的发件箱 blog:outbox:9993");

        // 验证大V没有推入粉丝收件箱
        Double bigBlogInFanInbox = stringRedisTemplate.opsForZSet().score(
                RedisConstants.FEED_KEY + FAN_USER_ID,
                bigVBlogId.toString()
        );
        assertNull(bigBlogInFanInbox, "大V笔记严禁写扩散到粉丝收件箱");

        System.out.println("笔记发布：小V写扩散推送与大V发件箱隔离机制验证通过！");
    }

    @Test
    @Order(4)
    @DisplayName("4. 关注流推拉聚合查询测试（热用户读取）")
    void testQueryBlogOfFollowHot() {
        System.out.println("========== 4. 测试粉丝关注页推拉聚合查询 ==========");
        UserHolder.saveUser(createUser(FAN_USER_ID, "测试粉丝-小明"));

        // 查询关注页流
        Result result = blogService.queryBlogOfFollow(System.currentTimeMillis() + 100000L, 0);
        assertTrue(result.getSuccess(), "查询关注流应该成功");
        ScrollResult scrollResult = (ScrollResult) result.getData();
        assertNotNull(scrollResult);

        List<?> list = scrollResult.getList();
        System.out.println("查询到的关注流笔记数量: " + list.size());
        assertTrue(list.size() >= 2, "关注流中应至少聚合出大V和小V各1篇笔记");

        // 校验笔记内容包含大V和小V发布的笔记
        boolean containsSmallVBlog = false;
        boolean containsBigVBlog = false;
        for (Object item : list) {
            Blog b = (Blog) item;
            if (b.getId().equals(smallVBlogId)) {
                containsSmallVBlog = true;
            }
            if (b.getId().equals(bigVBlogId)) {
                containsBigVBlog = true;
            }
        }
        assertTrue(containsSmallVBlog, "推拉聚合流必须包含小V笔记");
        assertTrue(containsBigVBlog, "推拉聚合流必须包含大V笔记");

        System.out.println("关注页热流推拉聚合验证通过！");
    }

    @Test
    @Order(5)
    @DisplayName("5. 粉丝冷启动兜底与收件箱重建测试（方案 B 互斥锁兜底）")
    void testColdStartAndRebuild() throws InterruptedException {
        System.out.println("========== 5. 测试冷启动与异步收件箱重建 ==========");
        UserHolder.saveUser(createUser(FAN_USER_ID, "测试粉丝-小明"));

        // 5.1 模拟冷用户：主动删除粉丝收件箱 feed:9991 与重建标记
        stringRedisTemplate.delete(RedisConstants.FEED_KEY + FAN_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FEED_REBUILD_MARK_KEY + FAN_USER_ID);
        Boolean hasInbox = stringRedisTemplate.hasKey(RedisConstants.FEED_KEY + FAN_USER_ID);
        assertFalse(Boolean.TRUE.equals(hasInbox), "收件箱已被清除，模拟冷启动状态");

        // 5.2 触发首次冷启动查询（方案 B 立即从 DB 捞取小V笔记并和大V合并返回，绝无白屏）
        long start = System.currentTimeMillis();
        Result coldResult = blogService.queryBlogOfFollow(System.currentTimeMillis() + 100000L, 0);
        long cost = System.currentTimeMillis() - start;
        System.out.println("冷启动首次查询耗时: " + cost + "ms");

        assertTrue(coldResult.getSuccess(), "冷启动查询应该成功");
        ScrollResult scrollResult = (ScrollResult) coldResult.getData();
        assertNotNull(scrollResult);
        assertFalse(scrollResult.getList().isEmpty(), "冷启动首屏兜底必须立即返回笔记数据，不能白屏");

        // 5.3 等待异步 RabbitMQ 重建任务完成 (2秒)
        System.out.println("等待异步 MQ 执行重建收件箱 feed:9991 ...");
        Thread.sleep(2000);

        // 5.4 验证收件箱已经被异步重建完成并重新存入 Redis
        Boolean inboxRebuilt = stringRedisTemplate.hasKey(RedisConstants.FEED_KEY + FAN_USER_ID);
        assertTrue(Boolean.TRUE.equals(inboxRebuilt), "冷启动后，MQ必须在后台成功异步重建 feed:9991");

        // 5.5 再次查询，此时应已命中热收件箱缓存
        Result hotResult = blogService.queryBlogOfFollow(System.currentTimeMillis() + 100000L, 0);
        assertTrue(hotResult.getSuccess());
        System.out.println("冷启动方案B瞬时返回与后台收件箱重建验证通过！");
    }

    @Test
    @Order(6)
    @DisplayName("6. 取消关注与收件箱清理测试")
    void testUnfollowAndCleanInbox() {
        System.out.println("========== 6. 测试取关与收件箱精准清理 ==========");
        UserHolder.saveUser(createUser(FAN_USER_ID, "测试粉丝-小明"));

        // 取关小V 9992
        Result unfollowRes = followService.follow(SMALL_V_USER_ID, false);
        assertTrue(unfollowRes.getSuccess(), "取关小V操作应该成功");

        // 验证数据库中关注关系已删除
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM tb_follow WHERE user_id = ? AND follow_user_id = ?",
                Integer.class, FAN_USER_ID, SMALL_V_USER_ID
        );
        assertEquals(0, count, "数据库中关注记录应已被删除");

        // 验证粉丝收件箱中已彻底移除了小V的历史笔记
        Double score = stringRedisTemplate.opsForZSet().score(
                RedisConstants.FEED_KEY + FAN_USER_ID,
                smallVBlogId.toString()
        );
        assertNull(score, "取关后，粉丝收件箱中已取关小V的笔记必须被精确清理，避免脏数据残留");

        // 再次查询流：仅剩大V笔记
        Result streamRes = blogService.queryBlogOfFollow(System.currentTimeMillis() + 100000L, 0);
        ScrollResult scroll = (ScrollResult) streamRes.getData();
        for (Object item : scroll.getList()) {
            Blog b = (Blog) item;
            assertNotEquals(smallVBlogId, b.getId(), "取关后关注流不应再展示小V的笔记");
        }

        System.out.println("取关与收件箱精准清理验证通过！");
    }

    @AfterAll
    void cleanup() {
        System.out.println("========== 7. 测试完成，清理测试数据 ==========");
        try {
            if (smallVBlogId != null && bigVBlogId != null) {
                jdbcTemplate.update("DELETE FROM tb_blog WHERE id IN (?, ?)", smallVBlogId, bigVBlogId);
            }
            cleanAllTestData();
            System.out.println("测试数据清理完毕！");
        } catch (Exception e) {
            System.out.println("清理数据警告: " + e.getMessage());
        }
    }

    private void cleanAllTestData() {
        jdbcTemplate.update("DELETE FROM tb_blog WHERE user_id IN (?, ?)", SMALL_V_USER_ID, BIG_V_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id = ? OR follow_user_id IN (?, ?)",
                FAN_USER_ID, SMALL_V_USER_ID, BIG_V_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (?, ?, ?)",
                FAN_USER_ID, SMALL_V_USER_ID, BIG_V_USER_ID);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id IN (?, ?, ?)",
                FAN_USER_ID, SMALL_V_USER_ID, BIG_V_USER_ID);

        stringRedisTemplate.delete(RedisConstants.FEED_KEY + FAN_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FAMOUS_FOLLOW_KEY + FAN_USER_ID);
        stringRedisTemplate.delete(RedisConstants.BLOG_OUTBOX_KEY + SMALL_V_USER_ID);
        stringRedisTemplate.delete(RedisConstants.BLOG_OUTBOX_KEY + BIG_V_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FANS_KEY + SMALL_V_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FANS_KEY + BIG_V_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FEED_REBUILD_LOCK_KEY + FAN_USER_ID);
        stringRedisTemplate.delete(RedisConstants.FEED_REBUILD_MARK_KEY + FAN_USER_ID);
    }
}

