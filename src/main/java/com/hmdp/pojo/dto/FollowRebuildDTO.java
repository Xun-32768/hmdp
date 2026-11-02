package com.hmdp.pojo.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class FollowRebuildDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long userId;
    private Long followUserId;
}
