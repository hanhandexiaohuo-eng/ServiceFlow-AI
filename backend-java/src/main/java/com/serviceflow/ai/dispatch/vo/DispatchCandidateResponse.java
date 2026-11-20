package com.serviceflow.ai.dispatch.vo;

import java.util.List;

/** 推荐候选：分数、分项、原因与警告全部可解释。 */
public record DispatchCandidateResponse(
        Long agentId,
        String name,
        String username,
        String availability,
        double loadRate,
        long occupied,
        int maxConcurrent,
        double score,
        ScoreBreakdown breakdown,
        List<String> matchedSkills,
        List<String> missingOptionalSkills,
        List<String> reasons,
        List<String> warnings
) {
}
