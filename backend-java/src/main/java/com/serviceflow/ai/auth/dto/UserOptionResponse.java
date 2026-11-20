package com.serviceflow.ai.auth.dto;

import com.serviceflow.ai.auth.entity.User;

/** 分配处理人下拉使用的最小用户信息，不包含密码等敏感字段。 */
public record UserOptionResponse(Long id, String username, String name, String role) {

    public static UserOptionResponse from(User u) {
        return new UserOptionResponse(u.getId(), u.getUsername(), u.getName(), u.getRole());
    }
}
