package com.serviceflow.ai.auth.dto;

import com.serviceflow.ai.auth.entity.User;

/** 用户对外视图：刻意不含 password，任何接口都不返回密码哈希。 */
public record UserResponse(Long id, String username, String name, String role) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getName(), u.getRole());
    }
}
