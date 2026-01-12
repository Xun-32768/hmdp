package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.User;

public interface IUserService extends IService<User> {
    Result sendCode(String phone);

    Result login(LoginFormDTO loginFormDTO);

}
