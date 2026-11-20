package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.AuthResult;
import com.serviceflow.ai.auth.dto.ChangePasswordRequest;
import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.TooManyRequestsException;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证业务测试：登录分支、锁定、强制改密、改密策略与初始管理员种子。
 * Repository/Jwt/Audit 用 mock；BCrypt 与限流器用真实实现（值行为，不该 mock）。
 * 测试中的初始密码在运行时随机生成，源码不出现任何固定凭据。
 */
class AuthServiceTest {

    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_REQUEST_ID = "test-request";
    /** 随机初始密码：满足长度但与任何真实环境无关 */
    private static final String ADMIN_INITIAL = "Pw-" + UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final LoginAttemptGuard loginAttemptGuard = new LoginAttemptGuard();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthService authService =
            new AuthService(userRepository, jwtService, loginAttemptGuard, auditLogService, ADMIN_INITIAL);

    private User user(String username, String rawPassword, String role, int status) {
        User u = new User();
        u.setId(1L);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setName("显示名");
        u.setRole(role);
        u.setStatus(status);
        u.setTokenVersion(0);
        u.setPasswordChangeRequired(0);
        return u;
    }

    private AuthResult login(String username, String password) {
        return authService.login(new LoginRequest(username, password), TEST_IP, TEST_REQUEST_ID);
    }

    @Test
    void loginWithUnknownUserReturns401() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> login("nobody", "whatever"));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void loginWithWrongPasswordReturns401WithSameMessage() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 1)));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> login("bob", "wrong-pass"));
        // 与用户不存在时提示完全一致，防止用户名枚举
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void loginSuccessReturnsTokenAndUser() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", "correct-pass", "ADMIN", 1)));
        when(jwtService.issueToken(any(User.class))).thenReturn("token-x");

        AuthResult result = login("admin", "correct-pass");

        assertEquals("token-x", result.token());
        assertNotNull(result.csrfToken());
        assertEquals("admin", result.body().user().username());
        assertEquals("ADMIN", result.body().user().role());
        assertFalse(result.body().passwordChangeRequired());
        verify(userRepository).recordLoginSuccess(1L);
        verify(auditLogService).success(eq(1L), eq("admin"), eq("LOGIN"), any(), any(), any(),
                eq(TEST_IP), eq(TEST_REQUEST_ID));
    }

    @Test
    void loginReportsPasswordChangeRequiredForSeededAdmin() {
        User admin = user("admin", "correct-pass", "ADMIN", 1);
        admin.setPasswordChangeRequired(1);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(jwtService.issueToken(any(User.class))).thenReturn("token-x");

        AuthResult result = login("admin", "correct-pass");

        assertTrue(result.body().passwordChangeRequired());
    }

    @Test
    void loginWithDisabledUserReturns401() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 0)));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> login("bob", "correct-pass"));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void loginWithOverlongPasswordTreatedAsMismatch() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 1)));
        String overlong = "a".repeat(100); // 超过 BCrypt 72 字节输入上限

        assertThrows(UnauthorizedException.class, () -> login("bob", overlong));
    }

    @Test
    void repeatedFailuresLockTheAccount() {
        User bob = user("bob", "correct-pass", "USER", 1);
        bob.setFailedLoginCount(4);
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        assertThrows(UnauthorizedException.class, () -> login("bob", "wrong-pass"));

        ArgumentCaptor<LocalDateTime> lockedUntil = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).recordLoginFailure(eq(1L), eq(5), lockedUntil.capture());
        assertNotNull(lockedUntil.getValue());
        assertTrue(lockedUntil.getValue().isAfter(LocalDateTime.now()));
    }

    @Test
    void lockedAccountReturns429() {
        User bob = user("bob", "correct-pass", "USER", 1);
        bob.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        assertThrows(TooManyRequestsException.class, () -> login("bob", "correct-pass"));
    }

    @Test
    void changePasswordRejectsWeakPassword() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(user("bob", "current-pass", "USER", 1)));

        assertThrows(IllegalArgumentException.class, () -> authService.changePassword(1L, "bob",
                new ChangePasswordRequest("current-pass", "short"), TEST_IP, TEST_REQUEST_ID));
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(user("bob", "current-pass", "USER", 1)));

        assertThrows(UnauthorizedException.class, () -> authService.changePassword(1L, "bob",
                new ChangePasswordRequest("wrong-pass", "NewStrongPass2026"), TEST_IP, TEST_REQUEST_ID));
    }

    @Test
    void changePasswordClearsForcedFlagAndReissuesSession() {
        User bob = user("bob", "current-pass", "USER", 1);
        bob.setPasswordChangeRequired(1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(bob));
        when(jwtService.issueToken(any(User.class))).thenReturn("token-new");

        AuthResult result = authService.changePassword(1L, "bob",
                new ChangePasswordRequest("current-pass", "NewStrongPass2026"), TEST_IP, TEST_REQUEST_ID);

        assertEquals("token-new", result.token());
        assertFalse(result.body().passwordChangeRequired());
        verify(userRepository).updatePassword(eq(1L), any());
        verify(auditLogService).success(eq(1L), eq("bob"), eq("PASSWORD_CHANGE"), any(), any(),
                eq("forced=true"), eq(TEST_IP), eq(TEST_REQUEST_ID));
    }

    @Test
    void logoutRevokesAllSessions() {
        authService.logout(1L, "bob", TEST_IP, TEST_REQUEST_ID);

        verify(userRepository).incrementTokenVersion(1L);
        verify(auditLogService).success(eq(1L), eq("bob"), eq("LOGOUT"), any(), any(), isNull(),
                eq(TEST_IP), eq(TEST_REQUEST_ID));
    }

    @Test
    void seedAdminWhenTableEmptyAndPasswordValid() {
        when(userRepository.count()).thenReturn(0L);
        // 模拟自增主键回填
        when(userRepository.insert(any())).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(1L);
            return u;
        });

        authService.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insert(captor.capture());
        User inserted = captor.getValue();
        assertEquals("admin", inserted.getUsername());
        assertEquals("ADMIN", inserted.getRole());
        assertEquals(1, inserted.getStatus());
        // 入库的是 BCrypt 哈希，不是明文
        assertNotEquals(ADMIN_INITIAL, inserted.getPassword());
        assertTrue(inserted.getPassword().startsWith("$2"));
        assertTrue(encoder.matches(ADMIN_INITIAL, inserted.getPassword()));
        // 首次登录必须改密
        verify(userRepository).markPasswordChangeRequired(1L);
    }

    @Test
    void seedSkippedWhenTableNotEmpty() {
        when(userRepository.count()).thenReturn(3L);

        authService.run(null);

        verify(userRepository, never()).insert(any());
    }

    @Test
    void seedSkippedWhenPasswordMissing() {
        AuthService noPassword =
                new AuthService(userRepository, jwtService, loginAttemptGuard, auditLogService, "");
        when(userRepository.count()).thenReturn(0L);

        noPassword.run(null);

        verify(userRepository, never()).insert(any());
    }

    @Test
    void seedSkippedWhenPasswordTooShort() {
        AuthService weak =
                new AuthService(userRepository, jwtService, loginAttemptGuard, auditLogService, "short");
        when(userRepository.count()).thenReturn(0L);

        weak.run(null);

        verify(userRepository, never()).insert(any());
    }

    @Test
    void seedDuplicateKeyIgnored() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.insert(any())).thenThrow(new DuplicateKeyException("uk_username"));

        assertDoesNotThrow(() -> authService.run(null));
    }
}
