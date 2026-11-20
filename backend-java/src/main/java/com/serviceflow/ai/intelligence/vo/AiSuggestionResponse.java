package com.serviceflow.ai.intelligence.vo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/** AI 建议响应。 */
public record AiSuggestionResponse(
        Long id,
        Long analysisId,
        Long ticketId,
        String type,
        String status,
        JsonNode payload,
        String summary,
        Long createdBy,
        String createdByName,
        Long decidedBy,
        String decidedByName,
        LocalDateTime decidedAt,
        String decisionNote,
        String executionNote,
        LocalDateTime createdAt
) {
}
