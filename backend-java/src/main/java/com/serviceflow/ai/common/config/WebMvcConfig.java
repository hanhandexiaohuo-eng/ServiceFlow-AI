package com.serviceflow.ai.common.config;

import com.serviceflow.ai.auth.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：注册认证拦截器。
 *
 * 公开路径：登录接口、健康检查（监控系统需要）。其余 /api/** 全部需要有效 Token。
 *
 * 关于 CORS：开发环境前端通过 Vite 代理访问后端（同源），生产环境前后端同源部署，
 * 因此不配置任何跨域放行，从源头避免"allowedOrigins(*)"这类危险默认值。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/health/**");
    }
}
