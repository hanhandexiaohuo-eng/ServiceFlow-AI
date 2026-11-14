package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.LoginRequest;
import com.serviceflow.ai.auth.dto.LoginResponse;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.jwt.JwtService;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证业务测试：登录分支 + 初始管理员种子。
 * Repository 和 JwtService 用 mock；BCryptPasswordEncoder 用真实实现（值行为，不该 mock）。
 */
class AuthServiceTest {

    private static final String ADMIN_PASSWORD = "init-pass-123";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthService authService = new AuthService(userRepository, jwtService, ADMIN_PASSWORD);

    private User user(String username, String rawPassword, String role, int status) {
        User u = new User();
        u.setId(1L);
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setName("显示名");
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void loginWithUnknownUserReturns401() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> authService.login(new LoginRequest("nobody", "whatever")));
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void loginWithWrongPasswordReturns401WithSameMessage() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 1)));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> authService.login(new LoginRequest("bob", "wrong-pass")));
        // 与用户不存在时提示完全一致，防止用户名枚举
        assertEquals("用户名或密码错误", e.getMessage());
    }

    @Test
    void loginSuccessReturnsTokenAndUser() {
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(user("admin", "correct-pass", "ADMIN", 1)));
        when(jwtService.issueToken(any(User.class))).thenReturn("token-x");

        LoginResponse resp = authService.login(new LoginRequest("admin", "correct-pass"));

        assertEquals("token-x", resp.token());
        assertEquals("admin", resp.user().username());
        assertEquals("ADMIN", resp.user().role());
    }

    @Test
    void loginWithDisabledUserReturns401() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 0)));

        UnauthorizedException e = assertThrows(UnauthorizedException.class,
                () -> authService.login(new LoginRequest("bob", "correct-pass")));
        assertEquals("账号已被禁用", e.getMessage());
    }

    @Test
    void loginWithOverlongPasswordTreatedAsMismatch() {
        when(userRepository.findByUsername("bob"))
                .thenReturn(Optional.of(user("bob", "correct-pass", "USER", 1)));
        String overlong = "a".repeat(100); // 超过 BCrypt 72 字节输入上限

        assertThrows(UnauthorizedException.class,
                () -> authService.login(new LoginRequest("bob", overlong)));
    }

    @Test
    void seedAdminWhenTableEmptyAndPasswordValid() {
        when(userRepository.count()).thenReturn(0L);

        authService.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).insert(captor.capture());
        User inserted = captor.getValue();
        assertEquals("admin", inserted.getUsername());
        assertEquals("ADMIN", inserted.getRole());
        assertEquals(1, inserted.getStatus());
        // 入库的是 BCrypt 哈希，不是明文
        assertNotEquals(ADMIN_PASSWORD, inserted.getPassword());
        assertTrue(inserted.getPassword().startsWith("$2"));
        assertTrue(encoder.matches(ADMIN_PASSWORD, inserted.getPassword()));
    }

    @Test
    void seedSkippedWhenTableNotEmpty() {
        when(userRepository.count()).thenReturn(3L);

        authService.run(null);

        verify(userRepository, never()).insert(any());
    }

    @Test
    void seedSkippedWhenPasswordMissing() {
        AuthService noPassword = new AuthService(userRepository, jwtService, "");
        when(userRepository.count()).thenReturn(0L);

        noPassword.run(null);

        verify(userRepository, never()).insert(any());
    }

    @Test
    void seedSkippedWhenPasswordTooShort() {
        AuthService weak = new AuthService(userRepository, jwtService, "short");
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
