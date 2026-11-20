package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员编辑用户资料（姓名、部门）。 */
public record UserProfileUpdateRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名最多 64 个字符")
        String name,

        @Size(max = 64, message = "部门最多 64 个字符")
        String department) {
}
