package com.hmdp;

import com.hmdp.config.GlobalExceptionHandler;
import com.hmdp.controller.UploadController;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class UploadTest {

    @Resource
    private UploadController uploadController;

    @Resource
    private IBlogService blogService;

    @Resource
    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(1001L);
        user.setNickName("测试上传用户");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("测试博客图片超过5MB单文件空间限制")
    void testUploadBlogImageExceedSize() {
        // 构造一个 6MB 的文件
        byte[] largeBytes = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", largeBytes);

        Result result = uploadController.uploadBlog(file);
        assertFalse(result.getSuccess(), "超出5MB应提示失败");
        assertTrue(result.getErrorMsg().contains("超过空间限制"), "错误信息应包含空间限制提示: " + result.getErrorMsg());
    }

    @Test
    @DisplayName("测试 Spring Multipart 抛出 MaxUploadSizeExceededException 全局拦截提示")
    void testMaxUploadSizeExceededExceptionHandler() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(10 * 1024 * 1024);
        Result result = globalExceptionHandler.handleMaxUploadSizeExceededException(ex);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("超过空间限制"), "全局异常处理应提示空间限制: " + result.getErrorMsg());
    }

    @Test
    @DisplayName("测试博客发布时超过9张图片的空间/数量限制")
    void testSaveBlogExceedImageCount() {
        Blog blog = new Blog();
        blog.setShopId(1L);
        blog.setTitle("探店超长图片测试");
        blog.setContent("很多图的一篇日记");
        // 构造 10 张图片字符串
        blog.setImages("1.jpg,2.jpg,3.jpg,4.jpg,5.jpg,6.jpg,7.jpg,8.jpg,9.jpg,10.jpg");

        Result result = blogService.saveBlog(blog);
        assertFalse(result.getSuccess(), "超过9张图应当失败");
        assertTrue(result.getErrorMsg().contains("超过限制") || result.getErrorMsg().contains("最多只能上传9张"),
                "错误信息应提示超出图片数量: " + result.getErrorMsg());
    }
}
