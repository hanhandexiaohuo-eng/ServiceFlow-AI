package com.serviceflow.ai.dispatch.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 调度推荐结果：推荐与正式分配分离，这里不改变工单状态。 */
public record DispatchRecommendationResponse(
        Long ticketId,
        String ticketNo,
        String title,
        String type,
        String priority,
        String status,
        String slaState,
        long slaRemainingMinutes,
        double slaUrgencyScore,
        List<SkillRequirementResponse> requiredSkills,
        List<DispatchCandidateResponse> candidates,
        List<DispatchExcludedResponse> excluded,
        DispatchWeightsResponse weights,
        LocalDateTime generatedAt
) {
}
