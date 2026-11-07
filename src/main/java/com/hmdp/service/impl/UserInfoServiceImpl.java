package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.dto.UserUpdateDTO;
import com.hmdp.pojo.entity.Blog;
import com.hmdp.pojo.entity.Follow;
import com.hmdp.pojo.entity.UserInfo;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
@Service
@Slf4j
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Resource
    @Lazy
    private IFollowService followService;

    @Resource
    @Lazy
    private IBlogService blogService;

    @Override
    public Result queryUserDetail(Long userId) {
        UserInfo info = getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setCity("杭州");
            info.setIntroduce("这个人很懒，什么都没有写");
            info.setCredits(0);
        }

        // 统计粉丝数与关注数
        long fansCount = followService.count(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, userId));
        long followeeCount = followService.count(new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, userId));
        info.setFans((int) fansCount);
        info.setFollowee((int) followeeCount);

        // 统计获赞总数（该用户发布的所有探店笔记的点赞总和）
        List<Blog> blogs = blogService.list(new LambdaQueryWrapper<Blog>().select(Blog::getLiked).eq(Blog::getUserId, userId));
        int totalLiked = 0;
        if (blogs != null) {
            totalLiked = blogs.stream().mapToInt(b -> b.getLiked() != null ? b.getLiked() : 0).sum();
        }
        info.setLiked(totalLiked);
        if (info.getCredits() == null) {
            info.setCredits(0);
        }
        // 会员等级与性别、生日兜底，保证返回结构完整（接口文档约定 level 为 0~9）
        if (info.getLevel() == null) {
            info.setLevel((byte) 0);
        }
        if (info.getFans() == null) {
            info.setFans(0);
        }
        if (info.getFollowee() == null) {
            info.setFollowee(0);
        }

        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    @Override
    public Result updateUserDetail(UserUpdateDTO updateDTO) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long userId = currentUser.getId();

        UserInfo info = getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setFans(0);
            info.setFollowee(0);
            info.setCredits(0);
            info.setLevel((byte) 0);
        }

        if (updateDTO.getCity() != null) {
            info.setCity(updateDTO.getCity().trim());
        }
        if (updateDTO.getIntroduce() != null) {
            info.setIntroduce(updateDTO.getIntroduce().trim());
        }
        if (updateDTO.getGender() != null) {
            info.setGender(updateDTO.getGender() ? (byte) 1 : (byte) 0);
        }
        if (StrUtil.isNotBlank(updateDTO.getBirthday())) {
            try {
                info.setBirthday(LocalDate.parse(updateDTO.getBirthday().trim()));
            } catch (Exception e) {
                log.warn("解析用户生日失败: {}", updateDTO.getBirthday());
            }
        }
        saveOrUpdate(info);
        return Result.ok();
    }
}
