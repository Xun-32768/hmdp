package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.PasswordDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserUpdateDTO;
import com.hmdp.pojo.entity.User;
import jakarta.servlet.http.HttpServletRequest;

public interface IUserService extends IService<User> {
    Result sendCode(String phone);

    Result login(LoginFormDTO loginFormDTO);

    Result logout(HttpServletRequest request);

    Result queryUserById(Long useId);

    Result updatePassword(PasswordDTO passwordDTO);

    Result resetPassword(PasswordDTO passwordDTO);

    Result sign();

    Result signCount();

    Result updateUserInfo(UserUpdateDTO updateDTO, HttpServletRequest request);
}
