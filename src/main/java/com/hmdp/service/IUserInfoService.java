package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserUpdateDTO;
import com.hmdp.pojo.entity.UserInfo;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
public interface IUserInfoService extends IService<UserInfo> {

    Result queryUserDetail(Long userId);

    Result updateUserDetail(UserUpdateDTO updateDTO);
}
