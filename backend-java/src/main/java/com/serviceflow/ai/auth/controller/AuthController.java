package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.AuthResult;
import com.serviceflow.ai.auth.dto.ChangePasswordRequest;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.service.AuthService;
import com.serviceflow.ai.auth.web.AuthCookies;
import com.serviceflow.ai.common.dto.ApiResponse;
import com.serviceflow.ai.common.web.ClientIp;
import com.serviceflow.ai.common.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口。会话令牌只写入 HttpOnly Cookie，响应体不返回 Token。
 * 写操作（改密、退出）由拦截器校验 CSRF 请求头。
 */
@Tag(name = "01 认证登录", description = "登录、当前用户、改密、退出")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    /** HTTPS 环境必须为 true；当前 HTTP 部署阶段由环境变量 COOKIE_SECURE=false 临时兼容 */
    @Value("${security.cookie.secure:true}")
    private boolean cookieSecure;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    @SecurityRequirements
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        AuthResult result = authService.login(req, ClientIp.resolve(request), currentRequestId(request));
        writeSessionCookies(response, result);
        return ApiResponse.ok(result.body());
    }

    /** 修改密码：首次登录强制改密也走这里 */
    @PostMapping("/password")
    public ApiResponse<LoginResponse> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        LoginUser current = UserContext.getRequired();
        AuthResult result = authService.changePassword(current.id(), current.username(), req,
                ClientIp.resolve(request), currentRequestId(request));
        writeSessionCookies(response, result);
        return ApiResponse.ok(result.body());
    }

    /** 退出：清除 Cookie 并让该用户所有已签发 Token 失效 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        LoginUser current = UserContext.getRequired();
        authService.logout(current.id(), current.username(),
                ClientIp.resolve(request), currentRequestId(request));
        clearSessionCookies(response);
        return ApiResponse.ok(null);
    }

    /** 返回当前登录用户信息（来自拦截器查库后写入的上下文，数据是请求时刻最新的） */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        LoginUser current = UserContext.getRequired();
        return ApiResponse.ok(new UserResponse(current.id(), current.username(), current.name(),
                current.role().name()));
    }

    private void writeSessionCookies(HttpServletResponse response, AuthResult result) {
        Duration maxAge = Duration.ofSeconds(jwtService.ttlSeconds());
        ResponseCookie session = ResponseCookie.from(AuthCookies.SESSION, result.token())
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(maxAge).build();
        // CSRF 双提交令牌需要被页面脚本读取后回填请求头，因此非 HttpOnly；它本身不是凭据
        ResponseCookie csrf = ResponseCookie.from(AuthCookies.CSRF, result.csrfToken())
                .httpOnly(false).secure(cookieSecure).sameSite("Lax").path("/").maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, session.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, csrf.toString());
    }

    private void clearSessionCookies(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        for (String name : new String[]{AuthCookies.SESSION, AuthCookies.CSRF}) {
            ResponseCookie cookie = ResponseCookie.from(name, "")
                    .httpOnly(AuthCookies.SESSION.equals(name))
                    .secure(cookieSecure).sameSite("Lax").path("/").maxAge(0).build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
    }

    private String currentRequestId(HttpServletRequest request) {
        String fromMdc = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return request.getHeader(RequestIdFilter.HEADER);
    }
}
