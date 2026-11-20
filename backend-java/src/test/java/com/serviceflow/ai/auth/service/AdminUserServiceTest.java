package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.dto.AdminUserCreateRequest;
import com.serviceflow.ai.auth.dto.UserPasswordResetRequest;
import com.serviceflow.ai.auth.dto.UserRoleUpdateRequest;
import com.serviceflow.ai.auth.dto.UserStatusUpdateRequest;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.auth.repository.UserRepository;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogService auditLogService;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, auditLogService);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private void loginAs(UserRole role, long id) {
        UserContext.set(new LoginUser(id, "actor", "Actor", role));
    }

    private User user(long id, String username, String role, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName("Name" + id);
        u.setRole(role);
        u.setStatus(status);
        u.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        return u;
    }

    @Test
    void 非管理员不能查询用户列表() {
        loginAs(UserRole.AGENT, 2);
        assertThatThrownBy(() -> service.page(null, null, null, 1, 10))
                .isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).search(anyString(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void 创建用户后必须强制改密且审计不含密码() {
        loginAs(UserRole.ADMIN, 1);
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.insert(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(9L);
            return u;
        });
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, "newbie", "AGENT", 1)));

        AdminUserCreateRequest req = new AdminUserCreateRequest(
                "newbie", "新人", "AGENT", "运维", "TempPass2026x");
        service.create(req, "127.0.0.1", "req-1");

        verify(userRepository).markPasswordChangeRequired(9L);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).success(eq(1L), eq("actor"), eq("USER_CREATE"), eq("USER"),
                eq("9"), detail.capture(), eq("127.0.0.1"), eq("req-1"));
        assertThat(detail.getValue()).doesNotContain("TempPass2026x");
    }

    @Test
    void 用户名为空或弱密码时拒绝创建() {
        loginAs(UserRole.ADMIN, 1);
        AdminUserCreateRequest req = new AdminUserCreateRequest(
                "weak", "弱口令", "USER", null, "123456");
        assertThatThrownBy(() -> service.create(req, "1.1.1.1", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).insert(any(User.class));
    }

    @Test
    void 不能停用自己() {
        loginAs(UserRole.ADMIN, 1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "actor", "ADMIN", 1)));
        assertThatThrownBy(() -> service.updateStatus(1L, new UserStatusUpdateRequest(0), "1.1.1.1", "r"))
                .isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).updateStatus(any(), anyInt());
    }

    @Test
    void 不能停用最后一个管理员() {
        loginAs(UserRole.ADMIN, 1);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "boss", "ADMIN", 1)));
        when(userRepository.countActiveAdmins()).thenReturn(1L);
        assertThatThrownBy(() -> service.updateStatus(2L, new UserStatusUpdateRequest(0), "1.1.1.1", "r"))
                .isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).updateStatus(any(), anyInt());
    }

    @Test
    void 不能修改自己的角色() {
        loginAs(UserRole.ADMIN, 1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "actor", "ADMIN", 1)));
        assertThatThrownBy(() -> service.updateRole(1L, new UserRoleUpdateRequest("USER"), "1.1.1.1", "r"))
                .isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).updateRoleAndInvalidate(any(), anyString());
    }

    @Test
    void 重置密码会强制改密且审计不含密码() {
        loginAs(UserRole.ADMIN, 1);
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "bob", "AGENT", 1)));

        service.resetPassword(2L, new UserPasswordResetRequest("ResetPass2026x"), "1.1.1.1", "r");

        verify(userRepository).resetPassword(eq(2L), anyString());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).success(eq(1L), eq("actor"), eq("USER_PASSWORD_RESET"), eq("USER"),
                eq("2"), detail.capture(), eq("1.1.1.1"), eq("r"));
        assertThat(detail.getValue()).doesNotContain("ResetPass2026x");
    }
}
