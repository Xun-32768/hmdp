package com.hmdp.controller;

import com.hmdp.pojo.dto.LoginFormDTO;
import com.hmdp.pojo.dto.Result;
import com.hmdp.pojo.dto.UserDTO;
import com.hmdp.pojo.entity.UserInfo;
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
     * 用户信息
     * @param useId
     * @return
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long useId) {
        UserInfo info=userInfoService.getById(useId);
        if(info==null){
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }
}
