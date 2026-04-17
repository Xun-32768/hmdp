package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    public Result follow(Long followUserId, Boolean isFollow){
        Long userId=UserHolder.getUser().getId();
        String key="follows:"+userId;
        if(isFollow){
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess=save(follow);
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else{
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("follow_user_id",followUserId).eq("user_id",userId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    public Result isFollow(Long followUserId){
        Long userId= UserHolder.getUser().getId();
        Long count=query().eq("user_id",userId).eq("follow_user_id",followUserId).count();
        return Result.ok(count>0);
    }

    public Result commonFollow(Long id){
        Long userId =UserHolder.getUser().getId();
        String key1="follows:"+userId;
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1,key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids=intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> users=userService.listByIds(ids)
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .toList();
        return Result.ok(users);
    }

}
