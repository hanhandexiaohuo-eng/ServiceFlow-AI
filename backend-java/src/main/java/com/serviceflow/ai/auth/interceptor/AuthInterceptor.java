package com.serviceflow.ai.auth.interceptor;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器：每个受保护请求都要过这一关。
 *
 * 校验分两层：
 * 1. Token 层：Authorization 头存在、Bearer 格式、签名有效、未过期 —— 不通过一律 401。
 * 2. 用户层：按 Token 里的 userId 查库，用户必须存在且未禁用 —— 保证禁用立即生效，
 *    且角色以数据库最新值为准（不信任 JWT 里的旧 role claim）。
 * 数据库故障（DataAccessException）不在这里捕获，交给全局异常处理器返回 500，
 * 绝不伪装成 401（那样会误导客户端跳转登录页）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthInterceptor(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 预检（OPTIONS）不带凭据，直接放行，由后续的 CORS 配置决定是否允许跨域
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        Claims claims;
        try {
            claims = jwtService.parse(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("未登录或登录已过期");
        }
        // 每请求查库：禁用立即生效、角色以 DB 为准。DB 异常会冒泡成 500（见类注释）。
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("未登录或登录已过期"));
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new UnauthorizedException("账号已被禁用");
        }
        UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getName(),
                UserRole.valueOf(user.getRole())));
        return true;
    }

    /**
     * 请求完成后（无论正常还是异常）清理 ThreadLocal。
     * Tomcat 线程复用时若不清除，下一个请求会读到上一个用户的信息（串号）。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.remove();
    }
}
