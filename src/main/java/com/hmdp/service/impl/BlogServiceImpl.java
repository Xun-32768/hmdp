package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.pojo.entity.User;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    public Result queryHotBlog(Integer current){
        Page<Blog> page =query().orderByDesc("liked").page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> blogs = page.getRecords();
        blogs.forEach(this::queryBlogUser);
        return Result.ok(blogs);
    }

    private void queryBlogUser(Blog blog) {
        Long userId=blog.getUserId();
        User user=userService.getById(userId);
        blog.setName(user.getNickName()).setIcon(user.getIcon());
    }

}
