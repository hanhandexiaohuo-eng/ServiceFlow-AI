package com.serviceflow.ai.dispatch.vo;

/** 工单类型对应的技能要求。 */
public record SkillRequirementResponse(
        Long skillId,
        String skillCode,
        String skillName,
        boolean required
) {
}
