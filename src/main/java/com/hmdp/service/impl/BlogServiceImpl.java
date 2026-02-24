package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.pojo.dto.Result;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            save(blog);
            //      推送到粉丝的“收件箱”（Feed流）
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


    public Result queryBlogById(Integer id){
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }


}
