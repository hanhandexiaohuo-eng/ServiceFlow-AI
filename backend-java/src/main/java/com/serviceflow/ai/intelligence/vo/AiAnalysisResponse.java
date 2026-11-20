package com.serviceflow.ai.intelligence.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.knowledge.vo.KnowledgeRecommendationResponse;

import java.time.LocalDateTime;
import java.util.List;

/** AI 分析响应：结构化结果 + 建议 + 知识推荐。 */
public record AiAnalysisResponse(
        Long id,
        Long ticketId,
        String ticketNo,
        String type,
        String status,
        Long requestedBy,
        String requestedByName,
        String promptVersion,
        String model,
        Integer latencyMs,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        String errorCode,
        String errorMessage,
        JsonNode result,
        JsonNode dispatch,
        List<AiSuggestionResponse> suggestions,
        List<KnowledgeRecommendationResponse> knowledge
) {
}
