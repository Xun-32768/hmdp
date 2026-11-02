package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /**
     * MinIO 服务端节点地址，如 http://127.0.0.1:9000
     */
    private String endpoint;

    /**
     * Access Key 用户名
     */
    private String accessKey;

    /**
     * Secret Key 密码
     */
    private String secretKey;

    /**
     * Bucket 存储桶名称
     */
    private String bucketName;

    /**
     * 外部访问的基础URL（可选，留空则默认使用 endpoint）
     */
    private String domain;
}
