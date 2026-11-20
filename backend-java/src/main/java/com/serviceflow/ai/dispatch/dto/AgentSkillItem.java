package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 单个技能配置：熟练度 1 初级 / 2 中级 / 3 高级 / 4 专家。 */
public record AgentSkillItem(
        @NotNull(message = "技能不能为空") Long skillId,
        @NotNull(message = "熟练度不能为空")
        @Min(value = 1, message = "熟练度取值范围 1-4")
        @Max(value = 4, message = "熟练度取值范围 1-4") Integer proficiencyLevel
) {
}
