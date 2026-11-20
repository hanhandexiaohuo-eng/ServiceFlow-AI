package com.serviceflow.ai.auth.controller;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.AuthResult;
import com.serviceflow.ai.auth.dto.ChangePasswordRequest;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.dto.UserResponse;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.service.AuthService;
import com.serviceflow.ai.common.exception.GlobalExceptionHandler;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 层测试：mock AuthService，验证 HTTP 语义、Cookie 下发与统一响应结构。
 * 关键断言：响应体永不出现 Token 或密码；会话只通过 HttpOnly Cookie 下发。
 */
class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final JwtService jwtService = mock(JwtService.class);

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
        when(jwtService.ttlSeconds()).thenReturn(3600L);
        return MockMvcBuilders
                .standaloneSetup(new AuthController(authService, jwtService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(contextStub())
                .build();
    }

    private AuthResult loginResult(boolean passwordChangeRequired) {
        return new AuthResult("session-token", "csrf-token",
                new LoginResponse(new UserResponse(1L, "admin", "系统管理员", "ADMIN"),
                        passwordChangeRequired));
    }

    @Test
    void loginWritesHttpOnlySessionCookieAndDoesNotLeakToken() throws Exception {
        when(authService.login(any(LoginRequest.class), any(), any())).thenReturn(loginResult(true));

        mockMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"xxx"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 响应体不返回 Token
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
                .andExpect(jsonPath("$.data.user.username").value("admin"))
                // 响应中绝不出现密码或哈希字段
                .andExpect(jsonPath("$.data.user.password").doesNotExist())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        hasItem(allOf(containsString("sf_session=session-token"), containsString("HttpOnly"),
                                containsString("SameSite=Lax")))))
                // CSRF Cookie 必须可被页面脚本读取（不能 HttpOnly）
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        hasItem(allOf(containsString("sf_csrf=csrf-token"), not(containsString("HttpOnly"))))));
    }

    @Test
    void csrfCookieIsReadableWhileSessionIsNot() throws Exception {
        when(authService.login(any(LoginRequest.class), any(), any())).thenReturn(loginResult(false));

        mockMvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"xxx"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        hasItem(allOf(containsString("sf_csrf=csrf-token"), not(containsString("HttpOnly"))))));
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
        when(authService.login(any(LoginRequest.class), any(), any()))
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
    void changePasswordReissuesSessionCookie() throws Exception {
        when(authService.changePassword(anyLong(), anyString(), any(ChangePasswordRequest.class),
                any(), any())).thenReturn(loginResult(false));

        mockMvc().perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-pass-123","newPassword":"NewStrongPass2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(false))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        hasItem(allOf(containsString("sf_session=session-token"), containsString("HttpOnly")))));
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        mockMvc().perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.name").value("系统管理员"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }
}
