package com.serviceflow.ai.auth.interceptor;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.auth.web.AuthCookies;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import com.serviceflow.ai.common.web.ClientIp;
import com.serviceflow.ai.common.web.RequestIdFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 认证拦截器：每个受保护请求都要过这一关。
 *
 * 校验顺序：
 * 1. 取凭据：优先 HttpOnly Cookie（浏览器），兼容 Bearer（脚本/接口测试）。
 * 2. Token 层：签名、过期、会话版本（token_version）—— 不通过一律 401。
 * 3. 用户层：按 userId 查库，必须存在且未禁用；角色以数据库为准。
 * 4. CSRF：Cookie 认证的写操作必须携带与 Cookie 一致的 X-CSRF-Token。
 * 5. 首登强制改密：只放行改密、退出、me 三个接口。
 * 数据库故障不在这里捕获，交给全局异常处理器返回 500，绝不伪装成 401。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 强制改密期间允许访问的路径 */
    private static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of(
            "/api/auth/password", "/api/auth/logout", "/api/auth/me");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AuthInterceptor(JwtService jwtService, UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String cookieToken = readCookie(request, AuthCookies.SESSION);
        boolean cookieAuth = cookieToken != null;
        String token = cookieAuth ? cookieToken : readBearer(request);
        String ip = ClientIp.resolve(request);
        String requestId = currentRequestId(request);

        if (token == null) {
            throw unauthorized(null, "missing_token", ip, requestId);
        }

        Claims claims;
        try {
            claims = jwtService.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw unauthorized(null, "invalid_token", ip, requestId);
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw unauthorized(null, "invalid_subject", ip, requestId);
        }

        // 每请求查库：禁用立即生效、角色以 DB 为准、会话版本可强制失效
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            throw unauthorized(null, "user_not_found", ip, requestId);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw unauthorized(user.getUsername(), "user_disabled", ip, requestId);
        }

        int tokenVersion = claims.get("ver", Integer.class) == null ? 0 : claims.get("ver", Integer.class);
        int currentVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (tokenVersion != currentVersion) {
            throw unauthorized(user.getUsername(), "session_revoked", ip, requestId);
        }

        if (cookieAuth && isUnsafeMethod(request.getMethod())
                && !csrfMatches(request)) {
            auditLogService.failure(user.getId(), user.getUsername(), "AUTH_FAILURE", "REQUEST",
                    request.getRequestURI(), null, ip, requestId, "csrf_failed");
            throw new ForbiddenException("CSRF 校验失败，请刷新页面后重试");
        }

        if (user.getPasswordChangeRequired() != null && user.getPasswordChangeRequired() == 1
                && !PASSWORD_CHANGE_ALLOWED.contains(request.getRequestURI())) {
            throw new ForbiddenException("请先修改初始密码");
        }

        UserContext.set(new LoginUser(user.getId(), user.getUsername(), user.getName(),
                UserRole.valueOf(user.getRole())));
        // 只把用户名放进 MDC 供访问日志与业务日志关联；不记录 Token
        MDC.put(RequestIdFilter.MDC_USER, user.getUsername());
        return true;
    }

    private UnauthorizedException unauthorized(String username, String reason, String ip, String requestId) {
        auditLogService.failure(null, username, "AUTH_FAILURE", "REQUEST", null, null, ip, requestId, reason);
        return new UnauthorizedException("未登录或登录已过期");
    }

    private boolean isUnsafeMethod(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method));
    }

    /** 双提交校验：请求头必须与 CSRF Cookie 完全一致（常量时间比较） */
    private boolean csrfMatches(HttpServletRequest request) {
        String header = request.getHeader(AuthCookies.CSRF_HEADER);
        String cookie = readCookie(request, AuthCookies.CSRF);
        if (header == null || cookie == null || header.isEmpty() || cookie.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                cookie.getBytes(StandardCharsets.UTF_8));
    }

    private String readBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private String currentRequestId(HttpServletRequest request) {
        String fromMdc = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return request.getHeader(RequestIdFilter.HEADER);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.remove();
        MDC.remove(RequestIdFilter.MDC_USER);
    }
}
