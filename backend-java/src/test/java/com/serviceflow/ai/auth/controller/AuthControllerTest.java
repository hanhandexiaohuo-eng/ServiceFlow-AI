package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.service.AuthService;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 层测试：mock AuthService，验证 HTTP 语义和统一响应结构。
 * /me 依赖登录上下文，用测试拦截器模拟真实拦截器写入 UserContext 的行为。
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);

    /** 模拟真实拦截器：写入上下文并在请求结束后清理 */
    private static HandlerInterceptor contextStub() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response, Object handler) {
                UserContext.set(new LoginUser(1L, "admin", "系统管理员", UserRole.ADMIN));
                return true;
            }

            @Override
            public void afterCompletion(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        Object handler, Exception ex) {
                UserContext.remove();
            }
        };
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(contextStub())
                .build();
    }

    @Test
    void loginSuccessReturns200WithToken() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("token-x",
                        new UserResponse(1L, "admin", "系统管理员", "ADMIN")));

        mockMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"xxx"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("token-x"))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                // 响应中绝不出现密码或哈希字段
                .andExpect(jsonPath("$.data.user.password").doesNotExist());
    }

    @Test
    void loginWithBlankParamsReturns400() throws Exception {
        mockMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"","password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new UnauthorizedException("用户名或密码错误"));

        mockMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mockMvc().perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.name").value("系统管理员"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
    }
}
