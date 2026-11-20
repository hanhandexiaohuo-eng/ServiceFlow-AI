package com.serviceflow.ai.dispatch.vo;

/**
 * 人员实时负载。数据全部来自 tickets / users / agent_profiles，不含任何随机或模拟值。
 * avgHandlingMinutes、avgSlaMinutes、slaAchievedRate 在无完成样本时为 null。
 */
public record WorkloadResponse(
        Long agentId,
        String name,
        String username,
        String role,
        Integer accountStatus,
        String availability,
        String effectiveAvailability,
        Integer maxConcurrent,
        long assignedCount,
        long acceptedCount,
        long processingCount,
        long occupied,
        double loadRate,
        String loadLevel,
        boolean canAccept,
        long todayCompleted,
        long weekCompleted,
        Long avgHandlingMinutes,
        Double avgSlaMinutes,
        Double slaAchievedRate,
        long completedSample
) {
}
