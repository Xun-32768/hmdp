package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ScrollResult;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.pojo.entity.User;
import com.hmdp.pojo.entity.UserInfo;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.pojo.dto.BlogPushDTO;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.hmdp.utils.RedisConstants.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-01-12
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 写扩散脚本：对一批收件箱执行 EXISTS 过滤 + ZADD + 裁剪 + EXPIRE。
     * <p>必须放在 Lua 里，因为 Pipeline 回调中命令只是入队，拿不到即时返回值，
     * 在 Java 侧做 {@code exists} 判断会永远得到 null/false。
     */
    private static final DefaultRedisScript<Long> FEED_PUSH_SCRIPT;
    /** 批量写入单个 ZSet 脚本：用于关注/重建时回填历史笔记 */
    private static final DefaultRedisScript<Long> FEED_BULK_SCRIPT;
    static {
        FEED_PUSH_SCRIPT = new DefaultRedisScript<>();
        FEED_PUSH_SCRIPT.setLocation(new ClassPathResource("feed_push.lua"));
        FEED_PUSH_SCRIPT.setResultType(Long.class);

        FEED_BULK_SCRIPT = new DefaultRedisScript<>();
        FEED_BULK_SCRIPT.setLocation(new ClassPathResource("feed_bulk.lua"));
        FEED_BULK_SCRIPT.setResultType(Long.class);
    }

//    查询与blog有关的用户
    private void queryBlogUser(Blog blog) {
        Long userId=blog.getUserId();
        User user = userId == null ? null : userService.getById(userId);
        if (user == null) {
            // 作者已被删除或数据异常时兜底，避免 NPE 导致整个列表接口 500
            blog.setName("未知用户").setIcon(null);
            return;
        }
        blog.setName(user.getNickName()).setIcon(user.getIcon());
    }
//  查询是否被点过赞
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // 未登录时按未点赞返回，保证 isLike 字段始终有值（接口文档约定为 false）
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        String key="blog:liked:"+blog.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(score != null);

    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> blogs = page.getRecords();
        blogs.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(blogs);
    }



    @Transactional(rollbackFor = Exception.class)
    public Result saveBlog(Blog blog){
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        if (blog.getContent() == null || blog.getShopId() == null || blog.getTitle() == null) {
            return Result.fail("笔记不完整，发布失败");
        }
        //            保存博客到数据库
        if (!save(blog)) {
            return Result.fail("发布失败");
        }
        Long blogId = blog.getId();
        long timeStamp = blog.getCreateTime() == null
                ? System.currentTimeMillis()
                : blog.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        // 1. 判定博主等级（优先依据 role_type 或 fans 计数）
        boolean isFamous = isFamousAuthor(userId);
        BlogPushDTO dto = isFamous ? null : new BlogPushDTO(blogId, userId, timeStamp);
        log.info("saveBlog: userId={}, isFamous={}, dto={}", userId, isFamous, dto);

        // 2. 推拉结合架构：
        //    - 大V：只写自身发件箱 blog:outbox:{userId}，不写任何粉丝维度数据（走读扩散）；
        //    - 小V：彻底不写发件箱（避免99%小博主发件箱无读取方造成的内存浪费），专走异步写扩散投递 MQ。
        //    发件箱写入与 MQ 都推迟到事务提交后执行。
        runAfterCommit(() -> {
            log.info("runAfterCommit executing: isFamous={}, dto={}", isFamous, dto);
            if (isFamous) {
                writeOutbox(userId, blogId, timeStamp);
            } else if (dto != null) {
                publishPush(dto);
            }
        });
        return Result.ok(blogId);
    }

    /**
     * 判断博主是否为大V。
     *
     * <p>优先读取 DB 中的 role_type（0-小V，1-大V），若未设置则依据粉丝数缓存与阈值。
     *
     * @param authorId 博主 ID
     * @return true 为大V，false 为小V
     */
    private boolean isFamousAuthor(Long authorId) {
        UserInfo authorInfo = userInfoMapper.getByUserId(authorId);
        if (authorInfo != null) {
            if (authorInfo.getRoleType() != null) {
                return authorInfo.getRoleType() == 1;
            }
            int fans = authorInfo.getFans() == null ? 0 : authorInfo.getFans();
            return fans >= FAMOUS_FAN_THRESHOLD;
        }
        return getAuthorFans(authorId) >= FAMOUS_FAN_THRESHOLD;
    }

    /**
     * 读取博主粉丝数，优先走 Redis 缓存。
     *
     * <p>粉丝数是发博分流与刷流大V判定的唯一依据，
     * 若每次都用 {@code SELECT COUNT(*)} 会随粉丝量增长拖垮数据库，
     * 因此读 {@code tb_user_info.fans} 并缓存 24 小时，
     * 由关注/取关链路主动失效保证时效性。
     *
     * @param authorId 博主 ID
     * @return 粉丝数，不存在时返回 0
     */
    private int getAuthorFans(Long authorId) {
        String cacheKey = USER_FANS_KEY + authorId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (Exception e) {
            log.warn("读取粉丝数缓存失败，降级查库，authorId={}", authorId, e);
        }
        // 缓存未命中：查单行字段（走主键，耗时可忽略）
        UserInfo authorInfo = userInfoMapper.getByUserId(authorId);
        int fans = (authorInfo == null || authorInfo.getFans() == null) ? 0 : authorInfo.getFans();
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey, String.valueOf(fans), USER_FANS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入粉丝数缓存失败，authorId={}", authorId, e);
        }
        return fans;
    }

    /**
     * 写入博主发件箱并裁剪到最新 {@link com.hmdp.utils.RedisConstants#BLOG_OUTBOX_MAX_LEN} 条。
     *
     * @param authorId   博主 ID
     * @param blogId     笔记 ID
     * @param timeStamp  发布时间戳（毫秒），作为 ZSet 分数
     */
    private void writeOutbox(Long authorId, Long blogId, long timeStamp) {
        String outboxKey = BLOG_OUTBOX_KEY + authorId;
        try {
            // 复用同一脚本：只操作单个 key，且 skipIfAbsent=0 表示不存在也创建
            stringRedisTemplate.execute(
                    FEED_PUSH_SCRIPT,
                    Collections.singletonList(outboxKey),
                    blogId.toString(),
                    String.valueOf(timeStamp),
                    String.valueOf(BLOG_OUTBOX_MAX_LEN),
                    String.valueOf(TimeUnit.DAYS.toSeconds(BLOG_OUTBOX_TTL_DAYS)),
                    "0"
            );
        } catch (Exception e) {
            // 发件箱写失败不能阻断发博：小博主仍有 MQ 写扩散兜底；
            // 大V 只靠发件箱被粉丝拉取，这条笔记会暂时不可见，需要人工补推。
            log.error("写入博主发件箱失败，authorId={}, blogId={}", authorId, blogId, e);
        }
    }

    /**
     * 在事务提交后执行 Feed 后续动作（没有活动事务时立即执行）。
     *
     * <p>Redis 写入与 MQ 投递都不能放在事务内：事务回滚后 Redis 已写入的条目无法回滚，
     * MQ 消费者也可能在 {@code tb_blog} 提交前取到消息。
     *
     * @param action 待执行动作
     */
    private void runAfterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Exception e) {
                log.error("事务提交后执行 Feed 后续动作失败", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }

    /**
     * 投递写扩散事件。
     *
     * @param dto 推送事件
     */
    private void publishPush(BlogPushDTO dto) {
        try {
            log.info("publishPush sending message: exchange={}, routingKey={}, dto={}",
                    RabbitMQConfig.BLOG_FEED_EXCHANGE, RabbitMQConfig.BLOG_FEED_PUSH_ROUTING_KEY, dto);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.BLOG_FEED_EXCHANGE,
                    RabbitMQConfig.BLOG_FEED_PUSH_ROUTING_KEY,
                    dto);
        } catch (Exception e) {
            log.error("投递 Feed 写扩散事件失败，blogId={}, authorId={}", dto.getBlogId(), dto.getAuthorId(), e);
        }
    }


    public Result queryBlogById(Long id){
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }


//TODo 处理高并发（短时间内重复点赞）
    //   修改点赞数量
    @SuppressWarnings("ConstantValue")
    @Override
    public  Result likeBlog(Long blogId){
//      获取当前用户id
        Long userId = UserHolder.getUser().getId();
//      判断当前登录用户是否已经点赞
        String key="blog:liked:"+blogId;
        Double score =stringRedisTemplate.opsForZSet().score(key,userId.toString());
//      未点赞过+1
        if(score == null){
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",blogId).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
        else {
//        点赞过-1
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", blogId).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();

    }

    //   点赞列表查询（查询top5）
    public Result queryBlogLikes(Long id){
        String key="blog:liked:"+id;
        Set<String> top5 =stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5== null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
//        获取点赞前五的用户id
        List<Long> ids=top5.stream().map(Long::valueOf).toList();
        String idStr= StrUtil.join(",",ids);
//       根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = query().eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        Page<Blog> page = query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long userId = currentUser.getId();
        long startTime = max == null ? System.currentTimeMillis() : max;
        int startOffset = offset == null || offset < 0 ? 0 : offset;
        long pageSize = FEED_PAGE_SIZE;

        String inboxKey = FEED_KEY + userId;
        boolean hasInbox = Boolean.TRUE.equals(stringRedisTemplate.hasKey(inboxKey));

        // 多源合并时不能把 offset 下推给每个数据源（会被放大 N 倍），
        // 因此各路先多取 offset 条，合并去重后再统一跳过 while 循环前的 offset 条
        long fetchLimit = pageSize + startOffset;

        // 大顶堆：score 越大（越新）越先出堆
        PriorityQueue<FeedEntry> heap = new PriorityQueue<>(
                (a, b) -> {
                    int byScore = Long.compare(b.score, a.score);
                    // 同分时按 blogId 排序，保证分页游标在多源合并下依然稳定
                    return byScore != 0 ? byScore : Long.compare(b.blogId, a.blogId);
                });

        if (hasInbox) {
            // 源 A（热用户）：从 Redis 个人收件箱读取小博主动态
            Set<ZSetOperations.TypedTuple<String>> inboxTuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(inboxKey, 0, startTime, 0, fetchLimit);
            enqueue(heap, inboxTuples);
        } else {
            // 方案 B（冷用户冷启动首刷）：
            // 1. 加分布式互斥锁（SETNX 10s），防止高频并发打爆 MySQL
            // 2. 抢到锁的线程同步查库获取关注小V最新动态放入堆中，保障首屏秒出不白屏
            // 3. 同时异步投递 MQ 触发收件箱原子回填
            boolean acquiredLock = tryAcquireRebuildLock(userId);
            if (acquiredLock) {
                try {
                    // 同步查库小V动态
                    loadSmallBloggerBlogsFromDb(userId, startTime, fetchLimit, heap);
                    // 异步触发收件箱回填
                    triggerRebuild(userId);
                } finally {
                    releaseRebuildLock(userId);
                }
            } else {
                // 未抢到锁说明并发请求正在重建，尝试读收件箱或异步等回填
                Set<ZSetOperations.TypedTuple<String>> inboxTuples = stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(inboxKey, 0, startTime, 0, fetchLimit);
                enqueue(heap, inboxTuples);
            }
        }

        // 源 B：关注的大V发件箱，O(1) 取出大V集合后用 Pipeline 一次性拉取，避免串行 N 次 RTT
        List<Long> famousIds = followService.queryFamousFollows(userId);
        if (famousIds != null && !famousIds.isEmpty()) {
            List<Object> pipeResults = pullFamousOutbox(famousIds, startTime, fetchLimit);
            for (Object raw : pipeResults) {
                if (raw instanceof Set<?> set) {
                    @SuppressWarnings("unchecked")
                    Set<ZSetOperations.TypedTuple<String>> tuples = (Set<ZSetOperations.TypedTuple<String>>) set;
                    enqueue(heap, tuples);
                }
            }
        }

        // 归并 + 去重 + 统一跳过 offset
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> scores = new ArrayList<>();
        int skipped = 0;
        while (ids.size() < pageSize && !heap.isEmpty()) {
            FeedEntry entry = heap.poll();
            if (!seen.add(entry.blogId)) {
                continue;
            }
            // 游标语义：score == startTime 的这批数据中，跳过前 offset 条（上一页已返回的部分）
            if (entry.score == startTime && skipped < startOffset) {
                skipped++;
                continue;
            }
            ids.add(entry.blogId);
            scores.add(entry.score);
        }
        if (ids.isEmpty()) {
            // 保留当前游标：重置成 offset=0 会让客户端从头再拉一遍同一批数据
            return Result.ok(emptyScrollResult(startTime, startOffset));
        }

        // 计算下一页游标：minTime 为本页最小时间戳。
        // 若整页都落在 startTime（上一页的边界时间戳）上，说明同一时间戳的记录还没取完，
        // offset 必须累计为 startOffset + 本页条数；否则下一页会跳过不足 offset 条而重复返回。
        long minTime = scores.get(scores.size() - 1);
        int nextOffset;
        if (minTime == startTime) {
            nextOffset = startOffset + scores.size();
        } else {
            nextOffset = 0;
            for (Long score : scores) {
                if (score == minTime) {
                    nextOffset++;
                }
            }
        }

        // 按 ids 顺序回查，ORDER BY FIELD 保证与堆序一致
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        blogs.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(nextOffset);
        return Result.ok(r);
    }

    /**
     * 方案 B：冷启动时加分布式互斥锁，防止并发刷流打崩 MySQL。
     */
    private boolean tryAcquireRebuildLock(Long userId) {
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                    FEED_REBUILD_LOCK_KEY + userId,
                    "1",
                    FEED_REBUILD_LOCK_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.error("获取 Feed 重建互斥锁失败，userId={}", userId, e);
            return false;
        }
    }

    /**
     * 释放分布式重建互斥锁。
     */
    private void releaseRebuildLock(Long userId) {
        try {
            stringRedisTemplate.delete(FEED_REBUILD_LOCK_KEY + userId);
        } catch (Exception e) {
            log.warn("释放 Feed 重建互斥锁异常，userId={}", userId, e);
        }
    }

    /**
     * 方案 B：同步从 MySQL 查询当前用户关注的小V的动态，装入归并大顶堆。
     *
     * <p>走 idx_user_create(user_id, create_time) 联合索引，毫秒级响应。
     */
    private void loadSmallBloggerBlogsFromDb(Long userId, long startTime, long fetchLimit, PriorityQueue<FeedEntry> heap) {
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) {
            return;
        }
        List<Long> allFollowIds = follows.stream().map(Follow::getFollowUserId).toList();
        // 排除大V
        List<Long> famousIds = followService.queryFamousFollows(userId);
        Set<Long> famousSet = famousIds == null ? Collections.emptySet() : new HashSet<>(famousIds);
        List<Long> smallFollowIds = allFollowIds.stream().filter(id -> !famousSet.contains(id)).toList();
        if (smallFollowIds.isEmpty()) {
            return;
        }

        LocalDateTime maxTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startTime), java.time.ZoneId.systemDefault());
        List<Blog> blogs = query()
                .select("id", "create_time")
                .in("user_id", smallFollowIds)
                .le("create_time", maxTime)
                .orderByDesc("create_time")
                .last("LIMIT " + fetchLimit)
                .list();

        if (blogs != null) {
            for (Blog b : blogs) {
                if (b.getId() != null && b.getCreateTime() != null) {
                    long score = b.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    heap.offer(new FeedEntry(b.getId(), score));
                }
            }
        }
    }

    /**
     * 把 ZSet 元组放入大顶堆。
     */
    private void enqueue(PriorityQueue<FeedEntry> heap, Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples == null || tuples.isEmpty()) {
            return;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String value = tuple.getValue();
            Double score = tuple.getScore();
            if (value == null || score == null) {
                continue;
            }
            heap.offer(new FeedEntry(Long.valueOf(value), score.longValue()));
        }
    }

    /**
     * 用 Pipeline 批量拉取多个大V的发件箱，把 N 次网络往返压缩为 1 次。
     *
     * @param famousIds  大V 用户 ID 列表
     * @param startTime  时间上界
     * @param startOffset 同一时间戳的偏移量
     * @param pageSize   每路取多少条
     * @return 各路查询结果，元素为 {@code Set<TypedTuple<String>>}
     */
    @SuppressWarnings("unchecked")
    private List<Object> pullFamousOutbox(List<Long> famousIds, long startTime, long fetchLimit) {
        List<Object> results = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (Long famousId : famousIds) {
                    ops.opsForZSet().reverseRangeByScoreWithScores(
                            BLOG_OUTBOX_KEY + famousId, 0, startTime, 0, fetchLimit);
                }
                // Pipeline 必须返回 null，结果由框架按命令顺序回填
                return null;
            }
        });
        return results == null ? Collections.emptyList() : results;
    }

    /**
     * 触发收件箱异步重建（走 MQ，避免刷流请求被重建拖慢）。
     *
     * <p>收件箱为空时每次刷流都会走到这里，用 SETNX 标记做防抖：
     * 只有拿到标记的请求才投递消息，避免同一用户并发/连续刷流把 MQ 打满。
     *
     * @param userId 用户 ID
     */
    private void triggerRebuild(Long userId) {
        String markKey = FEED_REBUILD_MARK_KEY + userId;
        Boolean acquired;
        try {
            acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                    markKey, "1",
                    FEED_REBUILD_TRIGGER_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("写入 Feed 重建标记失败，userId={}", userId, e);
            return;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            // 已有请求触发了重建，本次不再重复投递
            return;
        }
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.FEED_REBUILD_EXCHANGE,
                    RabbitMQConfig.FEED_REBUILD_ROUTING_KEY,
                    userId);
        } catch (Exception e) {
            // 投递失败时释放标记，下次请求还能重新触发
            stringRedisTemplate.delete(markKey);
            log.error("触发 Feed 重建失败，userId={}", userId, e);
        }
    }

    /**
     * 归并用的最小单元：一篇笔记及其时间戳分数。
     */
    private static final class FeedEntry {
        private final Long blogId;
        private final long score;

        private FeedEntry(Long blogId, long score) {
            this.blogId = blogId;
            this.score = score;
        }
    }

    /**
     * 构造空的分页结果，保证 /blog/of/follow 始终返回 {list,minTime,offset} 结构。
     *
     * <p>offset 沿用请求值而不是重置为 0：否则客户端带着新游标再请求一次，
     * 会重新拿到已经返回过的同一批数据。
     *
     * @param max    当前请求的时间戳游标
     * @param offset 当前请求的同时间戳偏移量
     */
    private ScrollResult emptyScrollResult(Long max, int offset) {
        ScrollResult r = new ScrollResult();
        r.setList(Collections.emptyList());
        r.setMinTime(max == null ? 0L : max);
        r.setOffset(offset);
        return r;
    }

    @Override
    public void rebuildFeed(Long userId) {
        try {
            doRebuildFeed(userId);
        } finally {
            // 无论是否有数据可重建都续期标记：「没有关注 / 只关注大V / 近期无笔记」的用户
            // 收件箱永远不会被创建，不续期就会导致每次刷流都重新投递重建消息。
            try {
                stringRedisTemplate.opsForValue().set(
                        FEED_REBUILD_MARK_KEY + userId, "1",
                        FEED_REBUILD_MARK_TTL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("续期 Feed 重建标记失败，userId={}", userId, e);
            }
        }
    }

    /**
     * 重建收件箱的实际逻辑。
     *
     * @param userId 用户 ID
     */
    private void doRebuildFeed(Long userId) {
        // 1. 获取当前用户关注的所有博主 ID
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) return;

        // 2. 推拉结合：排除大V，只重建小博主动态（批量查 UserInfo 避免 N+1）
        List<Long> allFollowIds = follows.stream().map(Follow::getFollowUserId).toList();
        List<Long> famousIds = followService.queryFamousFollows(userId);
        Set<Long> famousSet = famousIds == null ? Collections.emptySet() : new HashSet<>(famousIds);
        List<Long> smallBloggerIds = allFollowIds.stream()
                .filter(id -> !famousSet.contains(id))
                .collect(Collectors.toList());
        if (smallBloggerIds.isEmpty()) return;

        // 3. 从数据库查询这些博主最近 7 天发布的笔记（最多 100 条）
        List<Blog> recentBlogs = query().in("user_id", smallBloggerIds)
                .gt("create_time", LocalDateTime.now().minusDays(7))
                .orderByDesc("create_time")
                .last("LIMIT 100")
                .list();
        if (recentBlogs == null || recentBlogs.isEmpty()) return;

        // 4. 批量写入收件箱 ZSet，裁剪到上限并续期 7 天
        String key = FEED_KEY + userId;
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(FEED_MAX_LEN));
        args.add(String.valueOf(TimeUnit.DAYS.toSeconds(FEED_INBOX_TTL_DAYS)));
        for (Blog blog : recentBlogs) {
            long timestamp = blog.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            args.add(blog.getId().toString());
            args.add(String.valueOf(timestamp));
        }
        try {
            stringRedisTemplate.execute(FEED_BULK_SCRIPT, Collections.singletonList(key), args.toArray());
        } catch (Exception e) {
            log.error("重建 Feed 收件箱失败，userId={}", userId, e);
        }
    }


}
