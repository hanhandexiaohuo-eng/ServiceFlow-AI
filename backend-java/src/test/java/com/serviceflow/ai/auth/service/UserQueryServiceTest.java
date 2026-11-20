package com.serviceflow.ai.auth.service;

import com.serviceflow.ai.auth.dto.UserOptionResponse;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户查询业务测试（纯单元，mock UserRepository）：
 * 覆盖处理人候选的角色过滤，以及按显示名解析时的角色与同名校验。
 */
class UserQueryServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserQueryService userQueryService = new UserQueryService(userRepository);

    private User user(long id, String username, String name, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setRole(role);
        u.setStatus(1);
        return u;
    }

    @Test
    void listAssignableExcludesNormalUsers() {
        when(userRepository.findAllActive()).thenReturn(List.of(
                user(1L, "admin", "管理员", "ADMIN"),
                user(2L, "agent", "处理人", "AGENT"),
                user(3L, "user", "普通用户", "USER")));

        List<UserOptionResponse> result = userQueryService.listAssignable();

        assertEquals(2, result.size());
        assertEquals(List.of("admin", "agent"),
                result.stream().map(UserOptionResponse::username).toList());
        assertEquals("AGENT", result.get(1).role());
        assertEquals("处理人", result.get(1).name());
    }

    @Test
    void requireActiveAssigneeRejectsNormalUser() {
        when(userRepository.findAllActive()).thenReturn(List.of(
                user(3L, "user", "普通用户", "USER")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userQueryService.requireActiveAssigneeByName("user"));
        assertEquals("处理人必须是管理员或处理人员：普通用户", e.getMessage());
    }

    @Test
    void duplicateDisplayNameIsRejected() {
        when(userRepository.findAllActive()).thenReturn(List.of(
                user(1L, "zhangsan", "张三", "ADMIN"),
                user(2L, "zhangsan2", "张三", "AGENT")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userQueryService.requireActiveByName("张三"));
        assertEquals("存在同名用户，请先唯一化显示名：张三", e.getMessage());
    }

    @Test
    void unknownNameIsRejected() {
        when(userRepository.findAllActive()).thenReturn(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> userQueryService.requireActiveByName("  "));
        assertEquals("处理人不能为空", e.getMessage());
    }
}
