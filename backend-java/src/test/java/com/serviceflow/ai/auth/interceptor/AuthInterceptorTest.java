package com.serviceflow.ai.auth.interceptor;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
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
 * 用 Spring 的 Mock 请求/响应对象。Jwt/Repository/Audit 用 mock。
 */
class AuthInterceptorTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuthInterceptor interceptor =
            new AuthInterceptor(jwtService, userRepository, auditLogService);

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

    private MockHttpServletRequest cookieRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setCookies(new Cookie("sf_session", "good"));
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
        u.setTokenVersion(0);
        u.setPasswordChangeRequired(0);
        return u;
    }

    private void stubValidBearer(String token, User user) {
        // 先构造好 claims 再 stub，避免在 thenReturn 参数里嵌套 stubbing（Mockito 会报 UnfinishedStubbing）
        Claims claims = claimsWithSubject(String.valueOf(user.getId()));
        when(jwtService.parse(token)).thenReturn(claims);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
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
        assertEquals("未登录或登录已过期", e.getMessage());
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
    void staleSessionVersionReturns401() {
        User user = activeUser(7L, "USER");
        user.setTokenVersion(3);
        Claims claims = claimsWithSubject("7");
        when(claims.get("ver", Integer.class)).thenReturn(2);
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(bearerRequest("good"), response, null));
        assertNull(UserContext.get());
    }

    @Test
    void matchingSessionVersionPasses() {
        User user = activeUser(7L, "USER");
        user.setTokenVersion(3);
        Claims claims = claimsWithSubject("7");
        when(claims.get("ver", Integer.class)).thenReturn(3);
        when(jwtService.parse("good")).thenReturn(claims);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertTrue(interceptor.preHandle(bearerRequest("good"), response, null));
    }

    @Test
    void cookieWriteWithoutCsrfTokenRejected() {
        User user = activeUser(7L, "USER");
        stubValidBearer("good", user);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(cookieRequest("POST", "/api/tickets"), response, null));
        assertEquals("CSRF 校验失败，请刷新页面后重试", e.getMessage());
    }

    @Test
    void cookieWriteWithMismatchedCsrfTokenRejected() {
        User user = activeUser(7L, "USER");
        stubValidBearer("good", user);
        MockHttpServletRequest request = cookieRequest("POST", "/api/tickets");
        request.setCookies(new Cookie("sf_session", "good"), new Cookie("sf_csrf", "expected"));
        request.addHeader("X-CSRF-Token", "forged");

        assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(request, response, null));
    }

    @Test
    void cookieWriteWithMatchingCsrfTokenPasses() {
        User user = activeUser(7L, "USER");
        stubValidBearer("good", user);
        MockHttpServletRequest request = cookieRequest("POST", "/api/tickets");
        request.setCookies(new Cookie("sf_session", "good"), new Cookie("sf_csrf", "abc123"));
        request.addHeader("X-CSRF-Token", "abc123");

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    void cookieReadDoesNotRequireCsrfToken() {
        User user = activeUser(7L, "USER");
        stubValidBearer("good", user);

        assertTrue(interceptor.preHandle(cookieRequest("GET", "/api/tickets"), response, null));
    }

    @Test
    void passwordChangeRequiredBlocksBusinessApis() {
        User user = activeUser(7L, "USER");
        user.setPasswordChangeRequired(1);
        stubValidBearer("good", user);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(bearerRequest("good"), response, null));
        assertEquals("请先修改初始密码", e.getMessage());
        assertNull(UserContext.get());
    }

    @Test
    void passwordChangeRequiredAllowsPasswordAndMeEndpoints() {
        User user = activeUser(7L, "USER");
        user.setPasswordChangeRequired(1);
        stubValidBearer("good", user);

        MockHttpServletRequest change = new MockHttpServletRequest("POST", "/api/auth/password");
        change.addHeader("Authorization", "Bearer good");
        assertTrue(interceptor.preHandle(change, response, null));

        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/auth/me");
        me.addHeader("Authorization", "Bearer good");
        assertTrue(interceptor.preHandle(me, response, null));
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
