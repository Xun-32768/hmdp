package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.pojo.entity.User;
import com.hmdp.pojo.entity.UserInfo;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserInfoService;
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
    @Resource
    private UserInfoMapper userInfoMapper;

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
    public List<Long> queryFamousFollows(Long userId){
// 1. 查询当前用户关注的所有人
        List<Follow> follows = query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> followIds = follows.stream().map(Follow::getFollowUserId).toList();

        // 2. 判定哪些是“大V”（示例：粉丝数 > 5000 的用户）
        return followIds.stream().filter(followId ->{
            UserInfo info = userInfoMapper.getByUserId(followId);
            return info != null && info.getFans() > 5000;}).toList();
    }


}
