package com.serviceflow.ai.auth.context;

import com.serviceflow.ai.auth.enums.UserRole;

/** 当前登录用户信息，存在 UserContext（ThreadLocal）里，随请求生命周期存在。 */
public record LoginUser(Long id, String username, String name, UserRole role) {
}
