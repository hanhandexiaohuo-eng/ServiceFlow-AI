package com.serviceflow.ai.intelligence.vo;

import java.time.LocalDateTime;

/** 分析摘要，用于历史列表与会话详情。 */
public record AiAnalysisBrief(
        Long id,
        Long ticketId,
        String ticketNo,
        String type,
        String status,
        String summary,
        String errorCode,
        String requestedByName,
        LocalDateTime createdAt
) {
}
