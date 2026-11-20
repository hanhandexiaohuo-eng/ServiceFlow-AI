package com.serviceflow.ai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 管理员创建用户：初始密码只写入，不在任何响应中回显。 */
public record AdminUserCreateRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需为 3-32 个字符")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "用户名只能包含字母、数字、点、下划线和短横线")
        String username,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 64, message = "姓名最多 64 个字符")
        String name,

        @NotBlank(message = "角色不能为空")
        String role,

        @Size(max = 64, message = "部门最多 64 个字符")
        String department,

        @NotBlank(message = "初始密码不能为空")
        String password) {
}
