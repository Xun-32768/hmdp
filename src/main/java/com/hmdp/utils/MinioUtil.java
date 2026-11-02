package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.MinioProperties;
import io.minio.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;

/**
 * MinIO 对象存储工具类
 */
@Slf4j
@Component
public class MinioUtil {

    @Resource
    private MinioClient minioClient;

    @Resource
    private MinioProperties minioProperties;

    /**
     * 上传输入流到 MinIO 并返回公开访问 URL
     *
     * @param inputStream 输入流
     * @param objectName  对象存储路径，如 blogs/2026/10/23/uuid.jpg
     * @param contentType MIME类型，如 image/jpeg
     * @param size        文件字节大小（若未知可传 -1）
     * @return 文件的公开访问 URL
     */
    public String upload(InputStream inputStream, String objectName, String contentType, long size) {
        String bucketName = minioProperties.getBucketName();
        try {
            // 1. 确保存储桶存在并具有公网只读权限
            ensureBucketExists(bucketName);

            if (objectName.startsWith("/")) {
                objectName = objectName.substring(1);
            }

            // 2. 上传对象到 MinIO
            long partSize = -1;
            if (size < 0) {
                partSize = 10 * 1024 * 1024; // 未知大小时设置分片大小为 10MB
            }

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, size, partSize)
                            .contentType(StrUtil.isNotBlank(contentType) ? contentType : "application/octet-stream")
                            .build()
            );

            // 3. 构建可访问的 URL
            String baseUrl = StrUtil.isNotBlank(minioProperties.getDomain())
                    ? minioProperties.getDomain().replaceAll("/+$", "")
                    : minioProperties.getEndpoint().replaceAll("/+$", "");

            return baseUrl + "/" + bucketName + "/" + objectName;
        } catch (Exception e) {
            log.error("MinIO 上传失败, bucket: {}, object: {}", bucketName, objectName, e);
            throw new RuntimeException("文件上传 MinIO 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 MinIO 删除对象
     *
     * @param fileUrlOrName 完整URL或object路径
     */
    public void delete(String fileUrlOrName) {
        if (StrUtil.isBlank(fileUrlOrName)) {
            return;
        }
        String bucketName = minioProperties.getBucketName();
        String objectName = fileUrlOrName;

        // 如果是完整 URL，解析出 objectName
        if (objectName.startsWith("http://") || objectName.startsWith("https://")) {
            try {
                URI uri = URI.create(objectName);
                String path = uri.getPath(); // 例如 /hmdp/blogs/xxx.jpg
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                if (path.startsWith(bucketName + "/")) {
                    objectName = path.substring(bucketName.length() + 1);
                } else {
                    objectName = path;
                }
            } catch (Exception e) {
                log.warn("解析 MinIO 文件 URL 异常: {}", fileUrlOrName);
            }
        }

        if (objectName.startsWith("/")) {
            objectName = objectName.substring(1);
        }

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
            log.info("MinIO 文件删除成功: bucket={}, object={}", bucketName, objectName);
        } catch (Exception e) {
            log.error("MinIO 删除文件失败: bucket={}, object={}", bucketName, objectName, e);
        }
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            ensureBucketExists(minioProperties.getBucketName());
        } catch (Exception e) {
            log.warn("MinIO 初始化配置存储桶公开策略异常: {}", e.getMessage());
        }
    }

    /**
     * 确保存储桶存在，并配置公网只读权限策略
     */
    public void ensureBucketExists(String bucketName) throws Exception {
        boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!found) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            log.info("MinIO 自动创建存储桶: {}", bucketName);
        }

        // 无论桶是新建还是已存在，均确保配置公开只读策略（避免浏览器因 403 无法加载图片）
        try {
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
                          "Resource": ["arn:aws:s3:::%s"]
                        },
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(bucketName, bucketName);
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder()
                            .bucket(bucketName)
                            .config(policy)
                            .build()
            );
            log.info("MinIO 存储桶 {} 已成功配置公开只读访问策略", bucketName);
        } catch (Exception e) {
            log.warn("设置 MinIO 存储桶公开策略失败: {}", e.getMessage());
        }
    }
}
