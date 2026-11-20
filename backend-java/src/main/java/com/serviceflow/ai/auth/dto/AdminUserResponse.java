package com.serviceflow.ai.auth.dto;

import com.serviceflow.ai.auth.entity.User;

import java.time.LocalDateTime;

/** 管理端用户视图：不含密码哈希、会话版本等内部字段。 */
public record AdminUserResponse(Long id, String username, String name, String role, Integer status,
                                String department, boolean passwordChangeRequired,
                                LocalDateTime lastLoginAt, LocalDateTime createdAt) {

    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(u.getId(), u.getUsername(), u.getName(), u.getRole(), u.getStatus(),
                u.getDepartment(),
                u.getPasswordChangeRequired() != null && u.getPasswordChangeRequired() == 1,
                u.getLastLoginAt(), u.getCreatedAt());
    }
}
