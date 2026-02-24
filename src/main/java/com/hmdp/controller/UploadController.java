package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.pojo.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final String pathName="D:\\nginx\\nginx-1.18.0\\html\\hmdp\\imgs\\";

    @PostMapping("/blog")
    public Result uploadBlog(@RequestParam("file") MultipartFile image) {
        try{
            String originalFilename = image.getOriginalFilename();
//            获取文件后缀
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if(StrUtil.isBlank(suffix) || !List.of("jpg", "png", "jpeg", "webp").contains(suffix.toLowerCase())){
                return Result.fail("仅支持图片格式");
            }
            String fileName=createNewFileName(suffix);
            image.transferTo(new File(pathName+fileName));
            return Result.ok(fileName);
        }catch (Exception e){
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlog(@RequestParam("name") String fileName) {
        log.debug("准备删除图片，接收到的参数: {}", fileName);
        if (fileName.contains("..") || fileName.contains("\\")) {
            return Result.fail("非法的文件路径");
        }
        File  file=new File(pathName+fileName);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String suffix) {
        String name= UUID.randomUUID().toString();
        int hash=name.hashCode();
        int d1=hash & 0xF;
        int d2=(hash >> 4) & 0xF;
        //判断文件是否已存在
        File dir=new File(pathName, StrUtil.format("/blogs/{}/{}",d1,d2));
        if(!dir.exists()){
            dir.mkdirs();
        }
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
