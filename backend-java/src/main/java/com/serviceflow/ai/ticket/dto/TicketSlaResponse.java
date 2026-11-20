package com.serviceflow.ai.ticket.dto;

import java.time.LocalDateTime;

/**
 * 后端 SLA 计算结果。前端不再本地推算截止时间，只负责展示。
 */
public record TicketSlaResponse(
        String policyLabel,
        int hours,
        LocalDateTime dueAt,
        String state,
        long remainingMinutes,
        int elapsedPercent,
        LocalDateTime completedAt
) {
}
