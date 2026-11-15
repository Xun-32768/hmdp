package com.hmdp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 所有接口
                .allowedOrigins("http://localhost:3000") // 允许的来源
                .allowedMethods("GET", "POST", "PUT", "DELETE") // 允许的请求方式
                .allowCredentials(true) // 是否允许携带 Cookie
                .maxAge(3600); // 预检请求有效期
    }
}
