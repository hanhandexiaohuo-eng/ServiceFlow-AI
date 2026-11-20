package com.serviceflow.ai.dispatch.vo;

/** 人员技能。 */
public record AgentSkillResponse(
        Long skillId,
        String skillCode,
        String skillName,
        Integer proficiencyLevel,
        String proficiencyLabel
) {
    public static String label(Integer level) {
        if (level == null) {
            return "未知";
        }
        return switch (level) {
            case 1 -> "初级";
            case 2 -> "中级";
            case 3 -> "高级";
            case 4 -> "专家";
            default -> "未知";
        };
    }
}
