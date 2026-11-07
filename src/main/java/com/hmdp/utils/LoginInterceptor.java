package com.hmdp.utils;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Pattern;

/**
 * 登录鉴权拦截器
 * <p>
 * 在 RefreshTokenInterceptor 之后执行，负责拦截需要登录的接口。
 * 对于接口文档中标注为「无需鉴权 / 可选鉴权」的只读 GET 接口（如笔记详情、
 * 用户公开信息、分类列表等），即使未登录也应放行，由业务层自行判空。
 */
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 允许匿名访问的只读接口（仅对 GET 生效，避免误放行写操作）
     */
    private static final Pattern PUBLIC_GET_URI = Pattern.compile(
            "^/user/\\d+$"            // GET /user/{id}         用户公开信息
                    + "|^/user/info/\\d+$"  // GET /user/info/{id}    用户详情统计
                    + "|^/blog/\\d+$"       // GET /blog/{id}         笔记详情
                    + "|^/blog/likes/\\d+$" // GET /blog/likes/{id}   点赞 TOP5
                    + "|^/blog/of/user$"    // GET /blog/of/user      指定用户笔记列表
                    + "|^/shop.*$"          // 商铺相关
                    + "|^/shop-type.*$"     // 商铺分类
                    + "|^/voucher/list/\\d+$" // 商铺优惠券列表
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 只读公开接口放行
        if ("GET".equalsIgnoreCase(request.getMethod())
                && PUBLIC_GET_URI.matcher(request.getRequestURI()).matches()) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
