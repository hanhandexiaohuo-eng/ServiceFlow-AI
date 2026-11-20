package com.serviceflow.ai.auth.dto;

/**
 * 登录/改密响应：不包含访问 Token（Token 仅通过 HttpOnly Cookie 下发）。
 * passwordChangeRequired=true 时，前端必须跳转改密页面，后端也只放行改密/退出/me 接口。
 */
public record LoginResponse(UserResponse user, boolean passwordChangeRequired) {
}
