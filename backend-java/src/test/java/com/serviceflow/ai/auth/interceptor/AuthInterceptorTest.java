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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拦截器测试：不走 MockMvc，直接调 preHandle/afterCompletion，
 * 用 Spring 的 Mock 请求/响应对象。JwtService 和 UserRepository 用 mock。
 */
class AuthInterceptorTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtService, userRepository);

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        // 测试自身也要清理，避免用例之间互相污染
        UserContext.remove();
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tickets");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private Claims claimsWithSubject(String sub) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(sub);
        return claims;
    }

    private User activeUser(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("user" + id);
        u.setName("用户" + id);
        u.setRole(role);
        u.setStatus(1);
        return u;
    }

    @Test
    void noTokenReturns401() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tickets");

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(request, response, null));
        assertEquals("未登录或登录已过期", e.getMessage());
    }

    @Test
    void invalidTokenReturns401() {
        when(jwtService.parse("bad-token")).thenThrow(new JwtException("bad"));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(bearerRequest("bad-token"), response, null));
        assertEquals("未登录或登录已过期", e.getMessage());
    }

    @Test
    void validTokenSetsContext() {
        Claims claims = claimsWithSubject("7");
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(activeUser(7L, "USER")));

        boolean pass = interceptor.preHandle(bearerRequest("good"), response, null);

        assertTrue(pass);
        LoginUser current = UserContext.get();
        assertEquals(7L, current.id());
        assertEquals("用户7", current.name());
        assertEquals(UserRole.USER, current.role());
    }

    @Test
    void disabledUserReturns401() {
        User disabled = activeUser(7L, "USER");
        disabled.setStatus(0);
        Claims claims = claimsWithSubject("7");
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(disabled));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(bearerRequest("good"), response, null));
        assertEquals("账号已被禁用", e.getMessage());
        // 禁用判定失败时不得留下上下文
        assertNull(UserContext.get());
    }

    @Test
    void deletedUserReturns401() {
        Claims claims = claimsWithSubject("7");
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(bearerRequest("good"), response, null));
    }

    @Test
    void roleComesFromDatabaseNotJwt() {
        // 模拟"角色已变更"：管理员把用户从 USER 提为 ADMIN，旧 Token 还在有效期内。
        // 拦截器以数据库最新角色为准，旧 Token 立即获得新权限。
        Claims claims = claimsWithSubject("7");
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(activeUser(7L, "ADMIN")));

        interceptor.preHandle(bearerRequest("good"), response, null);

        assertEquals(UserRole.ADMIN, UserContext.get().role());
    }

    @Test
    void optionsRequestPassThrough() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/tickets");

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    void afterCompletionClearsContext() {
        Claims claims = claimsWithSubject("7");
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(activeUser(7L, "USER")));
        interceptor.preHandle(bearerRequest("good"), response, null);

        interceptor.afterCompletion(bearerRequest("good"), response, null, null);

        assertNull(UserContext.get());
    }
}
