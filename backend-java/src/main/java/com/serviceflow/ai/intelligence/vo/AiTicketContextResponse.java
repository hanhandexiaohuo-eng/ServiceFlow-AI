package com.serviceflow.ai.intelligence.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.knowledge.vo.KnowledgeRecommendationResponse;
import com.serviceflow.ai.ticket.dto.TicketResponse;

import java.util.List;

/** AI 工作空间右侧栏：工单、最近分析、待确认建议、推荐知识、调度快照。 */
public record AiTicketContextResponse(
        TicketResponse ticket,
        List<AiAnalysisBrief> latestAnalyses,
        List<AiSuggestionResponse> pendingSuggestions,
        List<KnowledgeRecommendationResponse> knowledge,
        JsonNode dispatch
) {
}
