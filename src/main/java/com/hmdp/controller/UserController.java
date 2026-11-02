package com.hmdp.controller;

import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.PasswordDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.dto.UserUpdateDTO;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制类
 */

@RestController
@Slf4j
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送验证码
     *
     * @param phone
     * @return
     */
    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录
     *
     * @param loginFormDTO
     * @return token
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginFormDTO) {
        return userService.login(loginFormDTO);
    }

    /**
     * 退出登录
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        return userService.logout(request);
    }

    /**
     * 个人页面
     *
     * @return
     */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 每日签到
     */
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /**
     * 查询连续签到天数
     */
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }

    /**
     * 用户详情信息（包含粉丝、关注、获赞、积分汇总）
     * @param userId
     * @return
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        return userInfoService.queryUserDetail(userId);
    }

    //根据id查询用户
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long useId) {
        return userService.queryUserById(useId);
    }

    /**
     * 修改/设置密码（已登录用户）
     */
    @PutMapping("/password")
    public Result updatePassword(@RequestBody PasswordDTO passwordDTO) {
        return userService.updatePassword(passwordDTO);
    }

    /**
     * 重置密码（未登录用户，通过手机验证码）
     */
    @PostMapping("/password/reset")
    public Result resetPassword(@RequestBody PasswordDTO passwordDTO) {
        return userService.resetPassword(passwordDTO);
    }

    /**
     * 修改用户基本信息 (如昵称、头像)
     */
    @PutMapping("/info")
    public Result updateUserInfo(@RequestBody UserUpdateDTO userUpdateDTO, HttpServletRequest request) {
        return userService.updateUserInfo(userUpdateDTO, request);
    }

    /**
     * 修改用户详情信息 (如城市、简介、性别、生日)
     */
    @PutMapping("/detail")
    public Result updateUserDetail(@RequestBody UserUpdateDTO userUpdateDTO) {
        return userInfoService.updateUserDetail(userUpdateDTO);
    }
}
