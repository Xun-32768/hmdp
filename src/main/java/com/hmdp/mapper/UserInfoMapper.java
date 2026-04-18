package com.hmdp.mapper;

import com.hmdp.pojo.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author axun
 * @since 2026-02-24
 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

    @Select("select * from tb_user_info where user_id=#{id}")
    UserInfo getByUserId(Long id);
}
