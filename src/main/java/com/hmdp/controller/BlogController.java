package com.hmdp.controller;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 博客控制类
 * @author axun
 * @since 2026-01-12
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 获取热门博客
     * @param current
     * @return
     */
    @GetMapping("/hot")
    private Result queryHotBlog(@RequestParam(value="current",defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }
}
