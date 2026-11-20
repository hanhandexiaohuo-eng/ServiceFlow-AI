package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 修改密码：必须提供当前密码，新密码按 PasswordPolicy 校验 */
public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空") String currentPassword,
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
