package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.pojo.dto.Result;
import com.hmdp.utils.MinioUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private MinioUtil minioUtil;

    private static final String pathName = "D:\\nginx\\nginx-1.18.0\\html\\hmdp\\imgs\\";

    /**
     * 用户头像独立上传接口
     * 存放在 MinIO 的 avatars/ 目录，区分普通博客图片
     */
    @PostMapping("/avatar")
    public Result uploadAvatar(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("上传头像图片不能为空");
        }
        // 头像文件大小限制在 2MB 以内
        if (image.getSize() > 2 * 1024 * 1024) {
            return Result.fail("上传头像图片大小不能超过 2MB");
        }
        try {
            String originalFilename = image.getOriginalFilename();
            // 获取文件后缀
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (StrUtil.isBlank(suffix) || !List.of("jpg", "png", "jpeg", "webp", "gif").contains(suffix.toLowerCase())) {
                return Result.fail("仅支持图片格式（jpg, png, jpeg, webp, gif）");
            }

            // 按日期划分目录，隔离存储在 avatars/ 下
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String objectName = StrUtil.format("avatars/{}/{}.{}", datePath, UUID.randomUUID().toString().replace("-", ""), suffix.toLowerCase());

            // 上传到 MinIO 并返回完整的公开访问 URL
            String fileUrl = minioUtil.upload(image.getInputStream(), objectName, image.getContentType(), image.getSize());
            return Result.ok(fileUrl);
        } catch (Exception e) {
            log.error("头像上传失败", e);
            return Result.fail("头像上传失败：" + e.getMessage());
        }
    }

    @PostMapping("/blog")
    public Result uploadBlog(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("上传图片不能为空");
        }
        try {
            String originalFilename = image.getOriginalFilename();
            // 获取文件后缀
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (StrUtil.isBlank(suffix) || !List.of("jpg", "png", "jpeg", "webp", "gif").contains(suffix.toLowerCase())) {
                return Result.fail("仅支持图片格式（jpg, png, jpeg, webp, gif）");
            }

            // 按日期划分目录，生成唯一文件名
            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String objectName = StrUtil.format("blogs/{}/{}.{}", datePath, UUID.randomUUID().toString().replace("-", ""), suffix.toLowerCase());

            // 上传到 MinIO 并返回完整的公开访问 URL
            String fileUrl = minioUtil.upload(image.getInputStream(), objectName, image.getContentType(), image.getSize());
            return Result.ok(fileUrl);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail("文件上传失败：" + e.getMessage());
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlog(@RequestParam("name") String fileName) {
        log.debug("准备删除图片，接收到的参数: {}", fileName);
        if (StrUtil.isBlank(fileName)) {
            return Result.fail("文件名不能为空");
        }

        // 如果是 MinIO URL 或对象路径，调用 MinIO 删除
        if (fileName.startsWith("http://") || fileName.startsWith("https://") || fileName.startsWith("blogs/")) {
            minioUtil.delete(fileName);
            return Result.ok();
        }

        // 兼容清理旧版本地历史文件
        if (fileName.contains("..") || fileName.contains("\\")) {
            return Result.fail("非法的文件路径");
        }
        File file = new File(pathName + fileName);
        if (file.exists() && !file.isDirectory()) {
            FileUtil.del(file);
        }
        return Result.ok();
    }
}
