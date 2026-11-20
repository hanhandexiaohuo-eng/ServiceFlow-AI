package com.serviceflow.ai.auth.dto;

/** 登录/改密成功的服务端结果：Token 只用于写 HttpOnly Cookie，不进入 JSON 响应体 */
public record AuthResult(String token, String csrfToken, LoginResponse body) {
}
