package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建处理人员：二选一。
 * 1) 传 userId：把已有启用用户提升为 AGENT 并建档；
 * 2) 不传 userId：用 username/name/password 创建新的 AGENT 账号。
 * 请求体没有 role 字段，角色提升只能由服务端决定。
 */
public record AgentCreateRequest(
        Long userId,
        @Size(max = 32, message = "用户名最长 32 个字符") String username,
        @Size(max = 64, message = "姓名最长 64 个字符") String name,
        String password,
        @Min(value = 1, message = "最大并发至少为 1")
        @Max(value = 50, message = "最大并发最多为 50") Integer maxConcurrent,
        String availability,
        @Valid List<AgentSkillItem> skills
) {
}
