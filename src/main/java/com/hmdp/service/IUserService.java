package com.hmdp.service;

import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.Result;

public interface IUserService {
    Result sendCode(String phone);

    Result<String> login(LoginFormDTO loginFormDTO);
}
