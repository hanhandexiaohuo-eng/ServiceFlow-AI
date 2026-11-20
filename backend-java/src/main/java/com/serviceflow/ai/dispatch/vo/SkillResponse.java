package com.serviceflow.ai.dispatch.vo;

/** 技能字典项。 */
public record SkillResponse(
        Long id,
        String skillCode,
        String skillName,
        String description
) {
}
