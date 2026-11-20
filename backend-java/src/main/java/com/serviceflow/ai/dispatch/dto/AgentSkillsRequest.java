package com.serviceflow.ai.dispatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 全量替换人员技能。 */
public record AgentSkillsRequest(
        @NotNull(message = "技能列表不能为空") @Valid List<AgentSkillItem> skills
) {
}
