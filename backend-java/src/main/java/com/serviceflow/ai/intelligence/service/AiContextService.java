package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.repository.AiAnalysisRepository;
import com.serviceflow.ai.intelligence.repository.AiSuggestionRepository;
import com.serviceflow.ai.intelligence.vo.AiAnalysisBrief;
import com.serviceflow.ai.intelligence.vo.AiSuggestionResponse;
import com.serviceflow.ai.intelligence.vo.AiTicketContextResponse;
import com.serviceflow.ai.knowledge.service.KnowledgeService;
import com.serviceflow.ai.knowledge.vo.KnowledgeRecommendationResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.service.TicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI 工作空间右侧栏上下文：
 * 工单、最近分析、待确认建议、知识推荐、最近一次规则调度快照。
 * 所有子项都来自真实表，无数据时返回空数组或 null。
 */
@Service
public class AiContextService {

    private final AiTicketAccessGuard guard;
    private final TicketService ticketService;
    private final AiAnalysisRepository analysisRepository;
    private final AiSuggestionRepository suggestionRepository;
    private final KnowledgeService knowledgeService;
    private final AiMapper mapper;

    public AiContextService(AiTicketAccessGuard guard,
                            TicketService ticketService,
                            AiAnalysisRepository analysisRepository,
                            AiSuggestionRepository suggestionRepository,
                            KnowledgeService knowledgeService,
                            AiMapper mapper) {
        this.guard = guard;
        this.ticketService = ticketService;
        this.analysisRepository = analysisRepository;
        this.suggestionRepository = suggestionRepository;
        this.knowledgeService = knowledgeService;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public AiTicketContextResponse context(Long ticketId) {
        Ticket ticket = guard.requireAccessible(ticketId);
        List<AiAnalysisBrief> latest = analysisRepository.findLatestByTicket(ticketId, null, 5)
                .stream().map(mapper::toBrief).toList();
        List<AiSuggestionResponse> pending = suggestionRepository.findPendingByTicket(ticketId, 5)
                .stream().map(mapper::toSuggestion).toList();
        List<KnowledgeRecommendationResponse> knowledge =
                knowledgeService.recommendForTicket(ticket, List.of(), 5);
        // 调度快照属于管理员可见的业务数据，从最近一次 DISPATCH 分析中读取，不在页面加载时重新计算
        JsonNode dispatch = null;
        if (guard.isAdmin()) {
            dispatch = analysisRepository.findLatestByTicket(ticketId, "DISPATCH", 1).stream()
                    .findFirst()
                    .map(a -> mapper.readJson(a.getResultJson()).path("rule"))
                    .filter(node -> node.isObject())
                    .orElse(null);
        }
        return new AiTicketContextResponse(
                ticketService.toResponse(ticket), latest, pending, knowledge, dispatch);
    }
}
