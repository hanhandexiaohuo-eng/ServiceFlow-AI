package com.serviceflow.ai.dispatch.strategy;

import com.serviceflow.ai.dispatch.vo.WorkloadResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;

import java.util.Map;
import java.util.Set;

/** 评分输入：工单 SLA、类型所需技能、人员技能熟练度与实时负载。 */
public record ScoreInput(
        Set<Long> requiredSkillIds,
        Set<Long> optionalSkillIds,
        Map<Long, Integer> proficiencyBySkillId,
        WorkloadResponse workload,
        TicketSlaResponse sla,
        int slaHours
) {
}
