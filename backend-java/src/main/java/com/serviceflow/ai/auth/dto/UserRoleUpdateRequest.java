package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 角色调整：只接受 ADMIN / AGENT / USER。 */
public record UserRoleUpdateRequest(@NotBlank(message = "角色不能为空") String role) {
}
