package com.hmdp.service;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-01-12
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result saveBlog(Blog blog);

    Result queryBlogById(Long id);

    Result queryBlogLikes(Long id);

    Result likeBlog(Long blogId);

    Result queryMyBlog(Integer current);

    Result queryBlogByUserId(Integer current, Long id);

    Result queryBlogOfFollow(Long max, Integer offset);
}
