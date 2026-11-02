package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.UserMapper;
import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.PasswordDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.dto.UserUpdateDTO;
import com.hmdp.pojo.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    @Lazy
    private IBlogService blogService;

    @Resource
    @Lazy
    private IUserInfoService userInfoService;

    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;
    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setLocation(new ClassPathResource("sms.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
    }
    public Result sendCode(String phone){
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        // 核心参数设定：60秒内最多只能发 1 次短信
        long windowSizeInSec = 60L;
        int limitCount = 1;

        long now = System.currentTimeMillis();
        long windowStart = now - (windowSizeInSec * 1000);
        // 必须使用 UUID 拼接，保证并发下每个请求的 Member 绝对唯一
        String member = now + "-" + UUID.randomUUID().toString();
        String key="sms:limit:"+phone;
        // 执行 Lua 脚本
        Long result = stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(windowStart),
                String.valueOf(limitCount),
                member,
                String.valueOf(windowSizeInSec)
        );
        // 结果校验
        if (result == null || result == 0L) {
            return Result.fail("获取验证码过于频繁，请60秒后再试");
        }

        String code= RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("验证码：{}", code);
        return Result.ok();
    }

    public Result login(LoginFormDTO loginFormDTO) {
        if (loginFormDTO == null) {
            return Result.fail("参数不能为空");
        }
        // 1.获取手机号、密码和验证码
        String phone = loginFormDTO.getPhone();
        String code = loginFormDTO.getCode();
        String password = loginFormDTO.getPassword();

        // 2.统一校验手机号格式
        if (StrUtil.isBlank(phone) || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        User user = query().eq("phone", phone).one();

        // 3.判断是密码登录还是验证码登录
        if (StrUtil.isNotBlank(password)) {
            // 3.1 密码登录分支
            // 校验密码格式（4~32位字母、数字、下划线）
            if (RegexUtils.isPasswordInvalid(password)) {
                return Result.fail("密码格式不正确（需为4-32位字符）");
            }

            // 检查防暴力破解：连续输错次数锁定
            String errKey = LOGIN_PASSWORD_ERR_KEY + phone;
            String errCountStr = stringRedisTemplate.opsForValue().get(errKey);
            int errCount = StrUtil.isNotBlank(errCountStr) ? Integer.parseInt(errCountStr) : 0;
            if (errCount >= LOGIN_PASSWORD_MAX_RETRY) {
                return Result.fail("密码连续错误次数过多，账号已临时锁定" + LOGIN_PASSWORD_ERR_TTL + "分钟，请稍后再试或通过验证码登录");
            }

            // 用户不存在
            if (user == null) {
                return Result.fail("该手机号尚未注册，请先使用验证码登录");
            }

            // 检查用户是否设置了密码
            String userPassword = user.getPassword();
            if (StrUtil.isBlank(userPassword)) {
                return Result.fail("该账号尚未设置密码，请先使用验证码登录");
            }

            // 比对密码（MD5）
            String md5Password = DigestUtil.md5Hex(password);
            if (!md5Password.equals(userPassword)) {
                // 输错密码，记录失败次数
                Long currentCount = stringRedisTemplate.opsForValue().increment(errKey);
                if (currentCount != null && currentCount == 1L) {
                    stringRedisTemplate.expire(errKey, LOGIN_PASSWORD_ERR_TTL, TimeUnit.MINUTES);
                }
                long remain = Math.max(0, LOGIN_PASSWORD_MAX_RETRY - (currentCount != null ? currentCount : 1));
                if (remain == 0) {
                    return Result.fail("密码错误，账号已临时锁定" + LOGIN_PASSWORD_ERR_TTL + "分钟");
                }
                return Result.fail("手机号或密码错误，还可尝试" + remain + "次");
            }

            // 密码验证成功，清除错误重试记录
            stringRedisTemplate.delete(errKey);

        } else {
            // 3.2 验证码登录分支
            if (StrUtil.isBlank(code)) {
                return Result.fail("验证码不能为空");
            }
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (cacheCode == null || !code.equals(cacheCode)) {
                return Result.fail("验证码不正确或已过期");
            }
            // 验证码验证成功后立即删除，防重复利用
            stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

            // 用户不存在则自动注册
            if (user == null) {
                user = createUserWithPhone(phone);
            }
        }

        // 4.保存用户信息到 Redis 并返回 Token
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5.异步检查/重建 Feed 流
        checkAndRebuildFeed(user.getId());
        return Result.ok(token);
    }

    @Override
    public Result updatePassword(PasswordDTO passwordDTO) {
        if (passwordDTO == null || StrUtil.isBlank(passwordDTO.getNewPassword())) {
            return Result.fail("新密码不能为空");
        }
        String newPassword = passwordDTO.getNewPassword();
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            return Result.fail("新密码格式不正确（需为4-32位字符）");
        }

        // 获取当前登录用户
        UserDTO loginUser = UserHolder.getUser();
        if (loginUser == null) {
            return Result.fail("用户未登录");
        }
        User user = getById(loginUser.getId());
        if (user == null) {
            return Result.fail("用户不存在");
        }

        // 若用户已设置过密码，需先校验原密码
        if (StrUtil.isNotBlank(user.getPassword())) {
            if (StrUtil.isBlank(passwordDTO.getOldPassword())) {
                return Result.fail("请输入原密码");
            }
            String oldMd5 = DigestUtil.md5Hex(passwordDTO.getOldPassword());
            if (!oldMd5.equals(user.getPassword())) {
                return Result.fail("原密码不正确");
            }
        }

        // 更新新密码
        user.setPassword(DigestUtil.md5Hex(newPassword));
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);

        // 清除输错密码锁定缓存（如果有）
        stringRedisTemplate.delete(LOGIN_PASSWORD_ERR_KEY + user.getPhone());
        return Result.ok();
    }

    @Override
    public Result resetPassword(PasswordDTO passwordDTO) {
        if (passwordDTO == null) {
            return Result.fail("参数不能为空");
        }
        String phone = passwordDTO.getPhone();
        String code = passwordDTO.getCode();
        String newPassword = passwordDTO.getNewPassword();

        if (StrUtil.isBlank(phone) || RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        if (StrUtil.isBlank(code)) {
            return Result.fail("验证码不能为空");
        }
        if (StrUtil.isBlank(newPassword) || RegexUtils.isPasswordInvalid(newPassword)) {
            return Result.fail("新密码格式不正确（需为4-32位字符）");
        }

        // 校验短信验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !code.equals(cacheCode)) {
            return Result.fail("验证码不正确或已过期");
        }
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        User user = query().eq("phone", phone).one();
        if (user == null) {
            return Result.fail("该手机号尚未注册");
        }

        user.setPassword(DigestUtil.md5Hex(newPassword));
        user.setUpdateTime(LocalDateTime.now());
        updateById(user);

        // 重置成功后，清除该账号的密码输错锁定
        stringRedisTemplate.delete(LOGIN_PASSWORD_ERR_KEY + phone);
        return Result.ok();
    }
    private void checkAndRebuildFeed(Long userId) {
        String key = FEED_KEY + userId;
        // 1. 判断收件箱是否存在
        Boolean hasKey = stringRedisTemplate.hasKey(key);
        if (Boolean.FALSE.equals(hasKey)) {
            // 2. 发送消息到RabbitMQ队列进行重建
            rabbitTemplate.convertAndSend("feed.topic", "feed.rebuild", userId);
        }
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            token = request.getParameter("authorization");
        }
        if (StrUtil.isNotBlank(token)) {
            String key = RedisConstants.LOGIN_USER_KEY + token;
            stringRedisTemplate.delete(key);
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key: sign:userId:yyyyMM
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月的第几天（从1开始）
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取本月截至今天为止的所有签到记录（返回一个十进制数字）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. 循环遍历，统计连续签到天数
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone)
                .setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10))
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        this.save(user);
        return user;
    }

    public Result queryUserById(Long useId) {
        User user = getById(useId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result updateUserInfo(UserUpdateDTO updateDTO, HttpServletRequest request) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }
        Long userId = currentUser.getId();

        boolean needUpdate = false;
        User user = new User();
        user.setId(userId);

        String newNickName = updateDTO.getNickName();
        if (StrUtil.isNotBlank(newNickName)) {
            user.setNickName(newNickName.trim());
            needUpdate = true;
        }

        String newIcon = updateDTO.getIcon();
        if (StrUtil.isNotBlank(newIcon)) {
            user.setIcon(newIcon.trim());
            needUpdate = true;
        }

        if (needUpdate) {
            user.setUpdateTime(LocalDateTime.now());
            updateById(user);

            // 同步更新 Redis 中的登录 token 缓存与 ThreadLocal
            String token = request.getHeader("authorization");
            if (StrUtil.isNotBlank(token)) {
                String tokenKey = LOGIN_USER_KEY + token;
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(tokenKey))) {
                    if (StrUtil.isNotBlank(newNickName)) {
                        stringRedisTemplate.opsForHash().put(tokenKey, "nickName", newNickName.trim());
                        currentUser.setNickName(newNickName.trim());
                    }
                    if (StrUtil.isNotBlank(newIcon)) {
                        stringRedisTemplate.opsForHash().put(tokenKey, "icon", newIcon.trim());
                        currentUser.setIcon(newIcon.trim());
                    }
                }
            }
        }

        // 防御性兼容：若同时携带详情字段，一并更新详细信息
        if (updateDTO.getCity() != null || updateDTO.getIntroduce() != null
                || updateDTO.getGender() != null || StrUtil.isNotBlank(updateDTO.getBirthday())) {
            userInfoService.updateUserDetail(updateDTO);
        }

        return Result.ok();
    }
}
