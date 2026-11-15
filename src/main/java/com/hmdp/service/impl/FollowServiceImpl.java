package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.pojo.entity.User;
import com.hmdp.pojo.entity.UserInfo;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private UserInfoMapper userInfoMapper;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    @Lazy
    private IBlogService blogService;

    /** 批量写入单个 ZSet 脚本：关注后回填历史笔记 */
    private static final DefaultRedisScript<Long> FEED_BULK_SCRIPT;
    static {
        FEED_BULK_SCRIPT = new DefaultRedisScript<>();
        FEED_BULK_SCRIPT.setLocation(new ClassPathResource("feed_bulk.lua"));
        FEED_BULK_SCRIPT.setResultType(Long.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result follow(Long followUserId, Boolean isFollow){
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long userId = currentUser.getId();
        if (followUserId == null) {
            return Result.fail("关注目标用户不能为空");
        }
        if (followUserId.equals(userId)) {
            return Result.fail("不能关注自己");
        }
        String followKey = RedisConstants.FOLLOW_KEY + userId;
        String fansKey = RedisConstants.FANS_KEY + followUserId;

        if (Boolean.TRUE.equals(isFollow)) {
            // 1. 幂等检查：已关注则直接返回成功，避免重复插入
            Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (count != null && count > 0) {
                return Result.ok();
            }

            // 2. 关注上限校验（单人上限 500）
            Long followCount = query().eq("user_id", userId).count();
            if (followCount != null && followCount >= RedisConstants.FOLLOW_MAX_LIMIT) {
                return Result.fail("关注人数已达上限（最多关注 " + RedisConstants.FOLLOW_MAX_LIMIT + " 人）");
            }

            // 3. 写入 MySQL 关注表
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (!isSuccess) {
                return Result.fail("关注失败");
            }

            // 4. 更新数据库计数：被关注者粉丝数 +1，当前用户关注数 +1
            userInfoService.update()
                    .setSql("fans = fans + 1")
                    .eq("user_id", followUserId)
                    .update();
            userInfoService.update()
                    .setSql("followee = followee + 1")
                    .eq("user_id", userId)
                    .update();

            // 5. 不对称设计：查询被关注者当前角色与粉丝量
            UserInfo targetInfo = userInfoMapper.getByUserId(followUserId);
            int targetFans = (targetInfo == null || targetInfo.getFans() == null) ? 0 : targetInfo.getFans() + 1;
            boolean isFamous = (targetInfo != null && targetInfo.getRoleType() != null && targetInfo.getRoleType() == 1)
                    || targetFans >= RedisConstants.FAMOUS_FAN_THRESHOLD;

            // 检查是否达到大V晋升阈值（3500），若是则触发晋升流
            boolean needUpgrade = targetFans >= RedisConstants.FAMOUS_UPGRADE_THRESHOLD
                    && (targetInfo == null || targetInfo.getRoleType() == null || targetInfo.getRoleType() == 0);
            if (needUpgrade) {
                userInfoService.update().set("role_type", 1).eq("user_id", followUserId).update();
                isFamous = true;
            }

            final boolean targetIsFamous = isFamous;
            final boolean triggerUpgrade = needUpgrade;

            // 6. Redis 写入放到事务提交后：事务回滚时关注集合/粉丝集合不会留下脏数据
            runAfterCommit(() -> {
                // 写入全量 Redis 关注列表（必存，单人上限 500）
                stringRedisTemplate.opsForSet().add(followKey, followUserId.toString());
                // 粉丝数已 +1，主动失效博主粉丝数缓存，避免发博分流读到旧值
                stringRedisTemplate.delete(RedisConstants.USER_FANS_KEY + followUserId);

                if (targetIsFamous) {
                    // 大V博主：维护大V专属关注集合 famousFollows:{userId}，严禁在 Redis 记录粉丝（防 BigKey）
                    stringRedisTemplate.opsForSet().add(RedisConstants.FAMOUS_FOLLOW_KEY + userId, followUserId.toString());
                    stringRedisTemplate.delete(fansKey);
                } else {
                    // 小博主：在 Redis 中记录粉丝 ID，便于发博时快速异步推送
                    stringRedisTemplate.opsForSet().add(fansKey, userId.toString());
                    // 异步将该小博主最近 30 条动态拉入当前用户的活跃收件箱
                    CompletableFuture.runAsync(() -> pullBlogFromNewFollow(
                            RedisConstants.FEED_KEY + userId, userId, followUserId));
                }

                // 触发大V晋升异步事件（预热发件箱、清理粉丝集合、广播老粉丝 famousFollows）
                if (triggerUpgrade) {
                    rabbitTemplate.convertAndSend(
                            RabbitMQConfig.FEED_REBUILD_EXCHANGE,
                            RabbitMQConfig.FEED_UPGRADE_ROUTING_KEY,
                            followUserId);
                }
            });
        } else {
            // 取关逻辑
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id", followUserId).eq("user_id", userId));
            if (isSuccess) {
                // 扣减数据库计数（避免减为负数）
                userInfoService.update()
                        .setSql("fans = IF(fans > 0, fans - 1, 0)")
                        .eq("user_id", followUserId)
                        .update();
                userInfoService.update()
                        .setSql("followee = IF(followee > 0, followee - 1, 0)")
                        .eq("user_id", userId)
                        .update();

                // 取关后要清掉收件箱里该作者的笔记。收件箱最多 FEED_MAX_LEN 条，
                // 因此该作者残留在收件箱里的笔记必然落在其最近 FEED_MAX_LEN 篇之内。
                List<Long> blogIds = blogService.query()
                        .select("id")
                        .eq("user_id", followUserId)
                        .orderByDesc("create_time")
                        .last("LIMIT " + RedisConstants.FEED_MAX_LEN)
                        .list()
                        .stream()
                        .map(Blog::getId)
                        .toList();

                // 同样放到事务提交后，避免回滚后 Redis 已经删除关注关系
                runAfterCommit(() -> {
                    // 移除 Redis 全量关注列表
                    stringRedisTemplate.opsForSet().remove(followKey, followUserId.toString());
                    // 移除大V专属关注列表（若存在）
                    stringRedisTemplate.opsForSet().remove(RedisConstants.FAMOUS_FOLLOW_KEY + userId, followUserId.toString());
                    // 尝试从小博主粉丝列表移除（若存在）
                    stringRedisTemplate.opsForSet().remove(fansKey, userId.toString());
                    // 粉丝数已 -1，主动失效缓存
                    stringRedisTemplate.delete(RedisConstants.USER_FANS_KEY + followUserId);
                    // 清理收件箱，避免取关后仍刷到已取关博主的旧笔记
                    if (!blogIds.isEmpty()) {
                        Object[] blogIdStrs = blogIds.stream().map(Object::toString).toArray();
                        stringRedisTemplate.opsForZSet().remove(
                                RedisConstants.FEED_KEY + userId, blogIdStrs);
                    }
                });
            }
        }
        return Result.ok();
    }
    // 关注后拉取博主博客到收件箱
    private void pullBlogFromNewFollow(String feedKey,Long userId, Long followUserId) {
        // 从数据库查询该博主最近发布的 30 条笔记
        List<Blog> blogs = blogService.query()
                .eq("user_id", followUserId)
                .orderByDesc("create_time")
                .last("limit 30")
                .list();

        if (blogs == null || blogs.isEmpty()) return;

        // 批量写入收件箱：一次 EVAL 完成全部 ZADD + 裁剪 + 续期，避免逐条往返
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(RedisConstants.FEED_MAX_LEN));
        args.add(String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.FEED_INBOX_TTL_DAYS)));
        for (Blog blog : blogs) {
            long timestamp = blog.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            args.add(blog.getId().toString());
            args.add(String.valueOf(timestamp));
        }
        try {
            stringRedisTemplate.execute(FEED_BULK_SCRIPT, Collections.singletonList(feedKey), args.toArray());
        } catch (Exception e) {
            log.error("关注后回填收件箱失败，userId={}, followUserId={}", userId, followUserId, e);
        }
    }

    public Result isFollow(Long followUserId){
        Long userId= UserHolder.getUser().getId();
        Long count=query().eq("user_id",userId).eq("follow_user_id",followUserId).count();
        return Result.ok(count>0);
    }

    public Result commonFollow(Long id){
        Long userId =UserHolder.getUser().getId();
        String key1="follows:"+userId;
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1,key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids=intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> users=userService.listByIds(ids)
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .toList();
        return Result.ok(users);
    }

    @Override
    public List<Long> queryFamousFollows(Long userId){
        // 1. 优先从 Redis 专属大V关注集合 famousFollows:{userId} 直接读取（O(1) 效率）
        String famousKey = RedisConstants.FAMOUS_FOLLOW_KEY + userId;
        Set<String> famousMembers = stringRedisTemplate.opsForSet().members(famousKey);
        if (famousMembers != null && !famousMembers.isEmpty()) {
            return famousMembers.stream().map(Long::valueOf).toList();
        }

        // 2. 若 famousKey 尚不存在/未初始化，从全量关注集合或 DB 回退计算，并回填 famousFollows:{userId}
        Set<String> followStrSet = stringRedisTemplate.opsForSet().members(RedisConstants.FOLLOW_KEY + userId);
        List<Long> followIds;
        if (followStrSet != null && !followStrSet.isEmpty()) {
            followIds = followStrSet.stream().map(Long::valueOf).toList();
        } else {
            List<Follow> follows = query().eq("user_id", userId).list();
            if (follows == null || follows.isEmpty()) {
                return Collections.emptyList();
            }
            followIds = follows.stream().map(Follow::getFollowUserId).toList();
        }

        // 批量查询关注用户的 UserInfo，依据 role_type 或 fans 判定大V
        List<UserInfo> infoList = userInfoService.query().in("user_id", followIds).list();
        List<Long> famousIds = new ArrayList<>();
        if (infoList != null) {
            for (UserInfo info : infoList) {
                boolean isFamous = (info.getRoleType() != null && info.getRoleType() == 1)
                        || (info.getFans() != null && info.getFans() >= RedisConstants.FAMOUS_FAN_THRESHOLD);
                if (isFamous) {
                    famousIds.add(info.getUserId());
                }
            }
        }
        if (!famousIds.isEmpty()) {
            String[] ids = famousIds.stream().map(String::valueOf).toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(famousKey, ids);
        }
        return followIds.stream().filter(famousIds::contains).toList();
    }

    /**
     * 在事务提交后执行 Redis 操作（没有活动事务时立即执行）。
     *
     * <p>关注关系与计数器在 MySQL 事务中维护，Redis 侧的集合/收件箱不在同一事务里。
     * 若在事务内直接写 Redis，事务回滚后 Redis 会残留「已关注」状态或不该存在的收件箱内容，
     * 因此统一推迟到 {@code afterCommit}。回调中的异常只记录日志，不影响已提交的业务。
     *
     * @param action 待执行动作
     */
    private void runAfterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Exception e) {
                log.error("关注/取关后执行 Redis 操作失败", e);
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
}
