package com.hmdp.service;

import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result commonFollow(Long id);

    List<Long> queryFamousFollows(Long userId);
}
