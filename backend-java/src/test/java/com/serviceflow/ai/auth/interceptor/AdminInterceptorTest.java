package com.serviceflow.ai.auth.interceptor;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminInterceptorTest {

    private final AdminInterceptor interceptor = new AdminInterceptor();

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    @Test
    void adminCanPass() {
        UserContext.set(new LoginUser(1L, "admin", "管理员", UserRole.ADMIN));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void agentIsRejectedBeforeValidation() {
        UserContext.set(new LoginUser(2L, "bob", "处理人员", UserRole.AGENT));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("仅管理员");
    }

    @Test
    void userRoleIsRejected() {
        UserContext.set(new LoginUser(3L, "carol", "用户", UserRole.USER));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/ai/meta");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void optionsRequestIsAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/users");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
