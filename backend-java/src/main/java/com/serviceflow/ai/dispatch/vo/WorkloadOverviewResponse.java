package com.serviceflow.ai.dispatch.vo;

import java.time.LocalDateTime;

/** 人员负载汇总。 */
public record WorkloadOverviewResponse(
        int totalAgents,
        int availableAgents,
        int busyAgents,
        int offlineAgents,
        int disabledAgents,
        int lowLoad,
        int normalLoad,
        int highLoad,
        int fullLoad,
        double avgLoadRate,
        long totalOccupied,
        long unassignedPending,
        LocalDateTime generatedAt
) {
}
