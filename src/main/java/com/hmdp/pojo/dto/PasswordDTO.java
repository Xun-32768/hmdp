package com.hmdp.pojo.dto;

import lombok.Data;

@Data
public class PasswordDTO {
    /**
     * 旧密码（已登录用户修改密码时验证）
     */
    private String oldPassword;

    /**
     * 新密码
     */
    private String newPassword;

    /**
     * 手机号（未登录时重置密码使用）
     */
    private String phone;

    /**
     * 手机验证码（未登录时重置密码使用）
     */
    private String code;
}
