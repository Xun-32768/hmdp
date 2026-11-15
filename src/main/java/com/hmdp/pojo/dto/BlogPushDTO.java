package com.hmdp.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 博客发布后的异步推送事件 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogPushDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 博客 ID */
    private Long blogId;

    /** 博主（作者）ID */
    private Long authorId;

    /** 博客发布时间戳（毫秒） */
    private Long publishTime;
}
