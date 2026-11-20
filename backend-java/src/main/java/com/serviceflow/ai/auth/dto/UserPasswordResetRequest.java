package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 管理员重置密码：设置临时密码，强制下次登录修改；响应不回显密码。 */
public record UserPasswordResetRequest(@NotBlank(message = "临时密码不能为空") String password) {
}
