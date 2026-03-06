package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.ScrollResult;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.pojo.entity.User;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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

    @Autowired
    private RedisIdWorker redisIdWorker;

//    查询与blog有关的用户
    private void queryBlogUser(Blog blog) {
        Long userId=blog.getUserId();
        User user=userService.getById(userId);
        blog.setName(user.getNickName()).setIcon(user.getIcon());
    }
//  查询是否呗点过赞
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = user.getId();
        String key="blog:liked:"+blog.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(score != null);

    }

    public Result queryHotBlog(Integer current){
        Page<Blog> page =query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> blogs = page.getRecords();
        blogs.forEach(this::queryBlogUser);
        return Result.ok(blogs);
    }



    @Transactional(rollbackFor = Exception.class)
    public Result saveBlog(Blog blog){
        UserDTO user= UserHolder.getUser();
        blog.setUserId(user.getId());
        try {
            if(blog.getContent()==null ||  blog.getShopId() == null || blog.getTitle() == null ){
                return Result.fail("笔记不完整，发布失败");
            }
            save(blog);
            // 推送到粉丝的“收件箱”（Feed流）
            List<Follow> follows=followService.query().eq("follow_user_id",user.getId()).list();
            for(Follow follow:follows){
                Long userId=follow.getUserId();
                String key="feed:"+userId;
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }catch (Exception e){
            log.error("博客保存失败",e);
            return Result.fail("博客保存失败");
        }

        return Result.ok(blog.getId());

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

    public Result queryMyBlog(Integer current){
        UserDTO user = UserHolder.getUser();
        Page<Blog> page=query().eq("user_id",user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records=page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id){
        Page<Blog> page = query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        os = (minTime == max ? os : os + offset);
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
}
