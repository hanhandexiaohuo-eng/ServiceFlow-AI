package com.serviceflow.ai.auth.interceptor;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端拦截器：/api/admin/** 只允许 ADMIN。
 *
 * 为什么放在拦截器而不是只靠 service 校验：参数绑定与 Bean Validation 在 controller
 * 方法执行前完成，非管理员提交空 body 会先拿到 400，等于向越权者暴露了接口的入参结构。
 * 在拦截器阶段直接拒绝，统一返回 403。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        LoginUser user = UserContext.getRequired();
        if (user.role() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可访问");
        }
        return true;
    }
}
