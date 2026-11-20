package com.serviceflow.ai.dispatch.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 人员详情。id 即 userId。 */
public record AgentResponse(
        Long id,
        String username,
        String name,
        String role,
        Integer accountStatus,
        String availability,
        String effectiveAvailability,
        Integer maxConcurrent,
        List<AgentSkillResponse> skills,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
