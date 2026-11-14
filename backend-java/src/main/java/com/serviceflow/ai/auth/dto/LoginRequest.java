package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求。密码不限制最大长度，超长输入由 Service 层按"密码错误"处理（BCrypt 有 72 字节输入上限）。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
