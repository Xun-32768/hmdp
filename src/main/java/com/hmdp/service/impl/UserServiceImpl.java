package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.UserMapper;
import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Result sendCode(String phone){
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        String code= RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("验证码：{}", code);
        return Result.ok();
    }

    public Result login(LoginFormDTO loginFormDTO){
//      1.获取手机号和验证码
        String phone = loginFormDTO.getPhone();
        String code= loginFormDTO.getCode();

//        2.校验手机号和验证码
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        String cacheCode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY +phone);
        if( code==null || !code.equals(cacheCode)){
            return Result.fail("验证码不正确");
        }
//        3.用户是否存在
        User user=query().eq("phone",phone).one();
        if(user==null){
//            不存在创建新用户
            user=createUserWithPhone(phone);
        }
        String token= UUID.randomUUID().toString(true);
        UserDTO userDTO= BeanUtil.copyProperties(user, UserDTO.class);
//        User对象转换为Map,忽略空值字段转换为String
        Map<String ,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);

    }
   public Result logout(HttpServletRequest request){
        String token=request.getParameter("authorization");
        if(StrUtil.isBlank(token)){
            return Result.ok();
        }
        String key=RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.delete(key);
        return Result.ok();
   }


    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone).setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        this.save(user);
        return user;
    }
   public  Result queryUserById(Long useId){
        User user=getById(useId);
        if(user==null){
            return Result.ok();
        }
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        return Result.ok(userDTO);
   }


}
