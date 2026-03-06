package com.hmdp.controller;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 博客控制类
 *
 * @author axun
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    //  获取热门博客
    @GetMapping("/hot")
    private Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    //   保存博客
    @PostMapping
    private Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    //   根据Id查询探店博客
    @GetMapping("/{id}")
    private Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    //   修改点赞数量
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long blogId) {
        return blogService.likeBlog(blogId);
    }

    //   点赞列表查询（查询top5）
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    //  查询个人博客
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryMyBlog(current);
    }

    //    根据userId查询博客
    @GetMapping("/of/user")
    public Result queryBlogByUserId(@RequestParam(value = "current",defaultValue = "1") Integer current
            ,@RequestParam("id") Long id) {
        return blogService.queryBlogByUserId(current,id);
    }

    //  个人关注信息(Feed流实现）
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(@RequestParam(value="lastId") Long max,
                                    @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max,offset);
    }

}
