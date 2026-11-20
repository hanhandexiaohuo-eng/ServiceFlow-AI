package com.serviceflow.ai.common.config;

import com.serviceflow.ai.auth.interceptor.AdminInterceptor;
import com.serviceflow.ai.auth.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * MVC 配置：注册认证拦截器与可选跨域。
 *
 * 公开路径：登录接口、健康检查（监控系统需要）。其余 /api/** 全部需要有效 Token。
 *
 * 关于 CORS：开发环境前端通过 Vite 代理访问后端（同源），生产环境由 nginx 同源反代，
 * 因此默认不开放任何跨域。仅当部署形态确实前后端分域时，才通过环境变量
 * CORS_ALLOWED_ORIGINS 显式列出允许的来源（逗号分隔），绝不使用 "*"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AdminInterceptor adminInterceptor;

    /** 为空表示不开放跨域（默认）；多个来源用英文逗号分隔 */
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    public WebMvcConfig(AuthInterceptor authInterceptor, AdminInterceptor adminInterceptor) {
        this.authInterceptor = authInterceptor;
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/health/**");
        // 顺序在认证之后：先确定身份，再判断角色，避免未登录请求先拿到 403
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Request-Id", "X-CSRF-Token")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

