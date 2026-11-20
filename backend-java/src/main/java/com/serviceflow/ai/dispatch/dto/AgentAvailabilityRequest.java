package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.constraints.NotBlank;

/** 调整人员工作状态。 */
public record AgentAvailabilityRequest(
        @NotBlank(message = "人员状态不能为空") String availability
) {
}
