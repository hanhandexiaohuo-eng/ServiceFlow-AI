package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.audit.AuditLogService;
import com.serviceflow.ai.common.dto.PageResponse;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.dispatch.entity.AgentSkill;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.service.DispatchService;
import com.serviceflow.ai.dispatch.vo.DispatchRecommendationResponse;
import com.serviceflow.ai.intelligence.client.AiServiceClient;
import com.serviceflow.ai.intelligence.client.AiServiceException;
import com.serviceflow.ai.intelligence.config.AiProperties;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.dto.AiAnalysisRequest;
import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import com.serviceflow.ai.intelligence.entity.AiSession;
import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import com.serviceflow.ai.intelligence.enums.AnalysisType;
import com.serviceflow.ai.intelligence.governance.security.AiPiiMasker;
import com.serviceflow.ai.intelligence.governance.service.AiCallLogService;
import com.serviceflow.ai.intelligence.governance.service.AiCircuitBreaker;
import com.serviceflow.ai.intelligence.governance.service.AiQuotaService;
import com.serviceflow.ai.intelligence.governance.service.AiRoutePlan;
import com.serviceflow.ai.intelligence.governance.service.AiRouteResolver;
import com.serviceflow.ai.intelligence.repository.AiAnalysisRepository;
import com.serviceflow.ai.intelligence.repository.AiSessionRepository;
import com.serviceflow.ai.intelligence.repository.AiSuggestionRepository;
import com.serviceflow.ai.intelligence.vo.AiAnalysisBrief;
import com.serviceflow.ai.intelligence.vo.AiAnalysisResponse;
import com.serviceflow.ai.intelligence.vo.AiSuggestionResponse;
import com.serviceflow.ai.knowledge.service.KnowledgeService;
import com.serviceflow.ai.knowledge.vo.KnowledgeRecommendationResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.service.TicketSlaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 分析编排：权限 → 去重/限流 → 落 PENDING → 调用 Python → 成功落结构化结果与建议，失败落错误码。
 * 这里不修改工单：分类/优先级建议只是 PENDING 记录，必须经 AiSuggestionService 人工确认。
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);

    private final AiTicketAccessGuard guard;
    private final AiAnalysisRepository analysisRepository;
    private final AiSuggestionRepository suggestionRepository;
    private final AiSessionRepository sessionRepository;
    private final AiServiceClient client;
    private final KnowledgeService knowledgeService;
    private final DispatchService dispatchService;
    private final TicketSlaService slaService;
    private final TicketRepository ticketRepository;
    private final AgentSkillRepository skillRepository;
    private final AiRequestLimiter limiter;
    private final AiMapper mapper;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final AiRouteResolver routeResolver;
    private final AiPiiMasker piiMasker;
    private final AiQuotaService quotaService;
    private final AiCallLogService callLogService;
    private final AiCircuitBreaker circuitBreaker;
    private final AuditLogService auditLogService;
    /** 调用失败审计去重：同一场景 + 错误码 60 秒内只记一次，避免故障时刷爆审计表。 */
    private final Map<String, Long> failureAuditWindow = new ConcurrentHashMap<>();

    public AiAnalysisService(AiTicketAccessGuard guard,
                             AiAnalysisRepository analysisRepository,
                             AiSuggestionRepository suggestionRepository,
                             AiSessionRepository sessionRepository,
                             AiServiceClient client,
                             KnowledgeService knowledgeService,
                             DispatchService dispatchService,
                             TicketSlaService slaService,
                             TicketRepository ticketRepository,
                             AgentSkillRepository skillRepository,
                             AiRequestLimiter limiter,
                             AiMapper mapper,
                             AiProperties properties,
                             ObjectMapper objectMapper,
                             AiRouteResolver routeResolver,
                             AiPiiMasker piiMasker,
                             AiQuotaService quotaService,
                             AiCallLogService callLogService,
                             AiCircuitBreaker circuitBreaker,
                             AuditLogService auditLogService) {
        this.guard = guard;
        this.analysisRepository = analysisRepository;
        this.suggestionRepository = suggestionRepository;
        this.sessionRepository = sessionRepository;
        this.client = client;
        this.knowledgeService = knowledgeService;
        this.dispatchService = dispatchService;
        this.slaService = slaService;
        this.ticketRepository = ticketRepository;
        this.skillRepository = skillRepository;
        this.limiter = limiter;
        this.mapper = mapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.routeResolver = routeResolver;
        this.piiMasker = piiMasker;
        this.quotaService = quotaService;
        this.callLogService = callLogService;
        this.circuitBreaker = circuitBreaker;
        this.auditLogService = auditLogService;
    }

    public AiAnalysisResponse analyze(AiAnalysisRequest req) {
        AnalysisType type = AnalysisType.parse(req.type());
        if (type == AnalysisType.CHAT) {
            throw new IllegalArgumentException("追问请使用会话消息接口");
        }
        Ticket ticket = guard.requireAccessible(req.ticketId());
        if (type == AnalysisType.DISPATCH) {
            guard.requireAdmin();
        }
        LoginUser user = guard.currentUser();
        if (req.sessionId() != null) {
            assertSession(req.sessionId(), ticket.getId());
        }
        limiter.check(user.id());

        // 去重：同用户 + 同工单 + 同类型在窗口内已成功，直接返回旧结果（不重复计费）
        if (!req.forceRefresh()) {
            LocalDateTime since = LocalDateTime.now().minusSeconds(properties.getDedupeSeconds());
            Optional<AiAnalysis> recent = analysisRepository.findRecentSuccess(
                    ticket.getId(), type.name(), user.id(), since);
            if (recent.isPresent()) {
                return toResponse(recent.get());
            }
        }

        AiAnalysis analysis = execute(type, ticket, req.sessionId(), req.question());
        return toResponse(analysis);
    }

    /**
     * 执行一次模型调用并持久化。失败时记录 FAILED 后抛出 AiServiceException，
     * 调用方（分析接口 / 会话追问）据此返回明确错误；不写入任何伪造结果。
     */
    public AiAnalysis execute(AnalysisType type, Ticket ticket, Long sessionId, String question) {
        LoginUser user = guard.currentUser();
        // 配额在去重命中之后才检查（analyze 先查去重），避免缓存命中消耗额度
        quotaService.check(user.id(), type.name());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket", ticketPayload(ticket));
        payload.put("sla", slaPayload(ticket));
        Object dispatchRule = null;

        if (type == AnalysisType.TICKET_ANALYSIS) {
            payload.put("skills", skillPayload());
            payload.put("knowledge", knowledgeService.promptCandidates(ticket, 5));
        } else if (type == AnalysisType.SOLUTION) {
            payload.put("knowledge", knowledgeService.promptCandidates(ticket, 5));
        } else if (type == AnalysisType.DISPATCH) {
            // 复用第四阶段规则调度，保留其 RECOMMEND 日志；AI 只做解释，不分配
            DispatchRecommendationResponse rule = dispatchService.recommend(ticket.getId(), 5);
            if (rule.candidates() == null || rule.candidates().isEmpty()) {
                throw new IllegalArgumentException("当前没有可用的规则调度候选，无法进行 AI 辅助分析");
            }
            dispatchRule = rule;
            payload.put("dispatch", objectMapper.convertValue(rule, new TypeReference<Map<String, Object>>() {
            }));
        } else if (type == AnalysisType.CHAT) {
            payload.put("question", clip(question, 2000));
            payload.put("context", chatContext(ticket.getId()));
        }

        // 发送前统一脱敏：手机号/邮箱/证件号/银行卡/密钥形态都不会到达模型侧
        Map<String, Object> maskedPayload = piiMasker.maskPayload(payload);

        AiAnalysis analysis = new AiAnalysis();
        analysis.setTicketId(ticket.getId());
        analysis.setSessionId(sessionId);
        analysis.setAnalysisType(type.name());
        analysis.setStatus("PENDING");
        analysis.setRequestedBy(user.id());
        analysis.setRequestNote(type == AnalysisType.CHAT ? clip(question, 500) : null);
        analysis.setTicketSnapshot(snapshot(ticket));
        analysisRepository.insert(analysis);

        long started = System.currentTimeMillis();
        try {
            JsonNode response = invoke(pathOf(type), type.name(), maskedPayload, analysis.getId(), user, 1);
            int latencyMs = (int) (System.currentTimeMillis() - started);
            JsonNode result = response.path("result");
            String promptVersion = response.path("promptVersion").asText(null);
            String model = response.path("model").asText(null);

            String resultJson = type == AnalysisType.DISPATCH
                    ? objectMapper.writeValueAsString(Map.of("rule", dispatchRule, "assist", result))
                    : objectMapper.writeValueAsString(result);

            analysisRepository.markSuccess(analysis.getId(), promptVersion, model, latencyMs, resultJson);
            log.info("ai_analysis_done id={} type={} ticketId={} latencyMs={}",
                    analysis.getId(), type, ticket.getId(), latencyMs);
            createSuggestion(analysis, type, result);
            return analysisRepository.findById(analysis.getId()).orElse(analysis);
        } catch (AiServiceException e) {
            analysisRepository.markFailed(analysis.getId(), e.getCode(), clip(e.getMessage(), 500),
                    (int) (System.currentTimeMillis() - started));
            log.warn("ai_analysis_failed id={} type={} code={}", analysis.getId(), type, e.getCode());
            throw e;
        } catch (Exception e) {
            analysisRepository.markFailed(analysis.getId(), "AI_INTERNAL_ERROR", "AI 分析处理失败",
                    (int) (System.currentTimeMillis() - started));
            log.error("ai_analysis_error id={} type={} error={}",
                    analysis.getId(), type, e.getClass().getSimpleName());
            throw new AiServiceException("AI_INTERNAL_ERROR", "AI 分析处理失败，请稍后重试", 500);
        }
    }

    /**
     * 调用模型：命中治理路由时按供应商/模型下发参数，支持主备降级与熔断；
     * 未命中时沿用 Python 进程环境变量配置，保证治理未启用时线上行为不变。
     */
    private JsonNode invoke(String path, String scenario, Map<String, Object> payload,
                            Long analysisId, LoginUser user, int attempt) {
        AiRoutePlan plan = routeResolver.resolve(scenario).orElse(null);
        if (plan == null) {
            return client.call(path, Map.of("requestId", "analysis-" + analysisId, "payload", payload));
        }
        return invokeWithPlan(path, scenario, payload, analysisId, user, plan, attempt);
    }

    private JsonNode invokeWithPlan(String path, String scenario, Map<String, Object> payload,
                                    Long analysisId, LoginUser user, AiRoutePlan plan, int attempt) {
        if (circuitBreaker.isOpen(plan.circuitKey())) {
            if (plan.fallback() != null && !circuitBreaker.isOpen(plan.fallback().circuitKey())) {
                log.warn("ai_circuit_open scenario={} model={} fallback={}",
                        scenario, plan.model(), plan.fallback().model());
                return invokeWithPlan(path, scenario, payload, analysisId, user, plan.fallback(), attempt + 1);
            }
            throw new AiServiceException("AI_CIRCUIT_OPEN", "AI 服务暂时不可用，请稍后重试", 503);
        }
        long started = System.currentTimeMillis();
        try {
            JsonNode response = client.call(path, requestBody(plan, payload, analysisId));
            circuitBreaker.recordSuccess(plan.circuitKey());
            callLogService.recordSuccess(analysisId, scenario, plan, attempt,
                    (int) (System.currentTimeMillis() - started), response.path("usage"), user.id());
            return response;
        } catch (AiServiceException e) {
            circuitBreaker.recordFailure(plan.circuitKey());
            callLogService.recordFailure(analysisId, scenario, plan, attempt, e.getCode(),
                    (int) (System.currentTimeMillis() - started), user.id());
            auditFailure(scenario, plan, e.getCode(), user);
            if (plan.fallback() != null && isFallbackAllowed(e)) {
                log.warn("ai_fallback scenario={} from={} to={} code={}", scenario,
                        plan.model(), plan.fallback().model(), e.getCode());
                return invokeWithPlan(path, scenario, payload, analysisId, user, plan.fallback(), attempt + 1);
            }
            throw e;
        }
    }

    private Map<String, Object> requestBody(AiRoutePlan plan, Map<String, Object> payload, Long analysisId) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("baseUrl", plan.baseUrl());
        provider.put("apiKey", plan.apiKey());
        provider.put("model", plan.model());
        provider.put("temperature", plan.temperature());
        provider.put("maxTokens", plan.maxTokens());
        provider.put("timeoutSeconds", plan.timeoutSeconds());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "analysis-" + analysisId);
        body.put("payload", payload);
        body.put("provider", provider);
        return body;
    }

    private boolean isFallbackAllowed(AiServiceException e) {
        return !List.of("AI_DISABLED", "AI_CIRCUIT_OPEN", "AI_QUOTA_EXCEEDED").contains(e.getCode());
    }

    private void auditFailure(String scenario, AiRoutePlan plan, String code, LoginUser user) {
        String key = scenario + ":" + code;
        long now = System.currentTimeMillis();
        Long last = failureAuditWindow.get(key);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(1)) {
            return;
        }
        failureAuditWindow.put(key, now);
        auditLogService.failure(user.id(), user.username(), "AI_CALL_FAILURE", "AI_SCENARIO", scenario,
                "model=" + plan.model() + " provider=" + plan.providerCode(), null, null, code);
    }

    @Transactional(readOnly = true)
    public PageResponse<AiAnalysisBrief> page(Long ticketId, String type, String status, int page, int size) {
        LoginUser user = guard.currentUser();
        if (ticketId != null) {
            guard.requireAccessible(ticketId);
        }
        Long requestedBy = user.role() == UserRole.ADMIN ? null : user.id();
        String safeType = type == null || type.isBlank() ? null : AnalysisType.parse(type).name();
        String safeStatus = normalizeStatus(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<AiAnalysisBrief> list = analysisRepository
                .findPage(ticketId, safeType, safeStatus, requestedBy, (safePage - 1) * safeSize, safeSize)
                .stream().map(mapper::toBrief).toList();
        long total = analysisRepository.count(ticketId, safeType, safeStatus, requestedBy);
        return new PageResponse<>(list, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public AiAnalysisResponse get(Long id) {
        AiAnalysis analysis = analysisRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("AI 分析记录不存在：id=" + id));
        LoginUser user = guard.currentUser();
        if (user.role() != UserRole.ADMIN && !Objects.equals(analysis.getRequestedBy(), user.id())) {
            throw new ForbiddenException("无权查看该 AI 分析记录");
        }
        guard.requireAccessible(analysis.getTicketId());
        return toResponse(analysis);
    }

    public AiAnalysisResponse toResponse(AiAnalysis analysis) {
        List<AiSuggestionResponse> suggestions = suggestionRepository.findByAnalysisId(analysis.getId())
                .stream().map(mapper::toSuggestion).toList();
        JsonNode result = mapper.readJson(analysis.getResultJson());
        JsonNode dispatch = null;
        List<KnowledgeRecommendationResponse> knowledge = List.of();
        if ("SUCCESS".equals(analysis.getStatus())) {
            if ("DISPATCH".equals(analysis.getAnalysisType())) {
                JsonNode rule = result.path("rule");
                dispatch = rule.isObject() ? rule : null;
            }
            List<String> keywords = keywordsOf(result);
            if (!keywords.isEmpty()) {
                Ticket ticket = ticketRepository.findById(analysis.getTicketId()).orElse(null);
                if (ticket != null) {
                    knowledge = knowledgeService.recommendForTicket(ticket, keywords, 5);
                }
            }
        }
        return new AiAnalysisResponse(
                analysis.getId(), analysis.getTicketId(), analysis.getTicketNo(),
                analysis.getAnalysisType(), analysis.getStatus(), analysis.getRequestedBy(),
                analysis.getRequestedByName(), analysis.getPromptVersion(), analysis.getModel(),
                analysis.getLatencyMs(), analysis.getCreatedAt(), analysis.getCompletedAt(),
                analysis.getErrorCode(), analysis.getErrorMessage(), result, dispatch,
                suggestions, knowledge);
    }

    private void createSuggestion(AiAnalysis analysis, AnalysisType type, JsonNode result) {
        String suggestionType;
        String summary;
        switch (type) {
            case TICKET_ANALYSIS -> {
                suggestionType = "CLASSIFICATION";
                String recommendedType = result.path("recommendedType").asText("");
                double confidence = result.path("typeConfidence").asDouble(0);
                summary = "建议分类 " + recommendedType + "（置信度 " + Math.round(confidence * 100) + "%）";
            }
            case PRIORITY -> {
                suggestionType = "PRIORITY";
                String priority = result.path("recommendedPriority").asText("");
                int hours = result.path("suggestedResponseHours").asInt(0);
                summary = "建议优先级 " + priority + "（" + hours + " 小时内响应）";
            }
            case SOLUTION -> {
                suggestionType = "SOLUTION";
                summary = "处理方案：" + clip(result.path("problemAnalysis").asText(""), 180);
            }
            default -> {
                return;
            }
        }
        try {
            AiSuggestion suggestion = new AiSuggestion();
            suggestion.setAnalysisId(analysis.getId());
            suggestion.setTicketId(analysis.getTicketId());
            suggestion.setSuggestionType(suggestionType);
            suggestion.setPayload(objectMapper.writeValueAsString(result));
            suggestion.setSummary(clip(summary, 500));
            suggestion.setCreatedBy(analysis.getRequestedBy());
            suggestionRepository.insert(suggestion);
        } catch (Exception e) {
            // 建议写入失败不影响已成功的分析结果，但必须留下日志便于排查
            log.warn("ai_suggestion_persist_failed analysisId={} type={} error={}",
                    analysis.getId(), suggestionType, e.getClass().getSimpleName());
        }
    }

    private void assertSession(Long sessionId, Long ticketId) {
        AiSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("AI 会话不存在：id=" + sessionId));
        LoginUser user = guard.currentUser();
        if (user.role() != UserRole.ADMIN && !Objects.equals(session.getUserId(), user.id())) {
            throw new ForbiddenException("无权在该会话中发起分析");
        }
        if (!Objects.equals(session.getTicketId(), ticketId)) {
            throw new IllegalArgumentException("会话与工单不匹配");
        }
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new ConflictException("会话已归档，不能继续分析");
        }
    }

    private Map<String, Object> ticketPayload(Ticket ticket) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ticketNo", ticket.getTicketNo());
        map.put("title", clip(ticket.getTitle(), 200));
        map.put("description", clip(ticket.getDescription(), 4000));
        map.put("type", ticket.getType());
        map.put("priority", ticket.getPriority());
        map.put("status", ticket.getStatus());
        map.put("createdAt", ticket.getCreatedAt() == null ? null : ticket.getCreatedAt().toString());
        map.put("assignee", ticket.getAssignee());
        map.put("creator", ticket.getCreator());
        return map;
    }

    private Map<String, Object> slaPayload(Ticket ticket) {
        TicketSlaResponse sla = slaService.evaluate(ticket);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("policyLabel", sla.policyLabel());
        map.put("hours", sla.hours());
        map.put("dueAt", sla.dueAt() == null ? null : sla.dueAt().toString());
        map.put("state", sla.state());
        map.put("remainingMinutes", sla.remainingMinutes());
        map.put("elapsedPercent", sla.elapsedPercent());
        return map;
    }

    private List<Map<String, Object>> skillPayload() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentSkill skill : skillRepository.findAll()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("skillCode", skill.getSkillCode());
            map.put("skillName", skill.getSkillName());
            list.add(map);
        }
        return list;
    }

    /** 追问上下文：只带最近分析的类型、状态与摘要，不重复发送完整历史正文。 */
    private List<Map<String, Object>> chatContext(Long ticketId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AiAnalysis item : analysisRepository.findLatestByTicket(ticketId, null, 3)) {
            if ("CHAT".equals(item.getAnalysisType())) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", item.getAnalysisType());
            map.put("status", item.getStatus());
            map.put("summary", clip(mapper.summaryOf(item), 200));
            map.put("createdAt", item.getCreatedAt() == null ? null : item.getCreatedAt().toString());
            list.add(map);
        }
        return list;
    }

    private String snapshot(Ticket ticket) {
        try {
            return objectMapper.writeValueAsString(slaPayload(ticket));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> keywordsOf(JsonNode result) {
        JsonNode node = result.path("keywords");
        if (!node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) {
                list.add(item.asText());
            }
        });
        return list.stream().limit(8).toList();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase();
        if (!List.of("PENDING", "SUCCESS", "FAILED").contains(value)) {
            throw new IllegalArgumentException("分析状态不合法：" + status);
        }
        return value;
    }

    private String pathOf(AnalysisType type) {
        return switch (type) {
            case TICKET_ANALYSIS -> "/ai/ticket-analysis";
            case PRIORITY -> "/ai/priority-recommendation";
            case SOLUTION -> "/ai/solution-generation";
            case DISPATCH -> "/ai/dispatch-assistance";
            case CHAT -> "/ai/ticket-qa";
        };
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
