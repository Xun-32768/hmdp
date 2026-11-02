package com.hmdp.pojo.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户个人信息修改接收 DTO
 */
@Data
public class UserUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 头像地址 (URL)
     */
    private String icon;

    /**
     * 城市
     */
    private String city;

    /**
     * 个人介绍
     */
    private String introduce;

    /**
     * 性别（false/0: 男, true/1: 女）
     */
    private Boolean gender;

    /**
     * 生日（格式 YYYY-MM-DD）
     */
    private String birthday;
}
