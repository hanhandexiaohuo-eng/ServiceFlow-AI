package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.service.AuthService;
import com.serviceflow.ai.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。/api/auth/login 公开（拦截器排除）；/api/auth/me 需要有效 Token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    /** 返回当前登录用户信息（来自拦截器查库后写入的上下文，数据是请求时刻最新的） */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        LoginUser current = UserContext.getRequired();
        return ApiResponse.ok(new UserResponse(current.id(), current.username(), current.name(),
                current.role().name()));
    }
}
