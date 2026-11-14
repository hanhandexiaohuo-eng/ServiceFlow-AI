package com.serviceflow.ai.auth.dto;

/** 登录成功响应：访问令牌 + 当前用户信息。 */
public record LoginResponse(String token, UserResponse user) {
}
