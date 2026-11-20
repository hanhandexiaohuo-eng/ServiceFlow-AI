package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 修改人员档案：只允许最大并发与工作状态，用户名/角色不可改。 */
public record AgentUpdateRequest(
        @Min(value = 1, message = "最大并发至少为 1")
        @Max(value = 50, message = "最大并发最多为 50") Integer maxConcurrent,
        String availability
) {
}
