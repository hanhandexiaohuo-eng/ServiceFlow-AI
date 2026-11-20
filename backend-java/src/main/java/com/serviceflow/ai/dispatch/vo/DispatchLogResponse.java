package com.serviceflow.ai.dispatch.vo;

import java.time.LocalDateTime;

/** 调度历史记录。 */
public record DispatchLogResponse(
        Long id,
        Long ticketId,
        String ticketNo,
        String action,
        Long operatorId,
        String operatorName,
        Long agentId,
        String agentName,
        Double score,
        Integer candidateCount,
        String note,
        String detail,
        LocalDateTime createdAt
) {
}
