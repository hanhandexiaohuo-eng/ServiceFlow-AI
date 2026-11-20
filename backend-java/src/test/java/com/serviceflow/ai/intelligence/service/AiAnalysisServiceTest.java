package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.dispatch.repository.AgentSkillRepository;
import com.serviceflow.ai.dispatch.service.DispatchService;
import com.serviceflow.ai.intelligence.client.AiServiceClient;
import com.serviceflow.ai.intelligence.client.AiServiceException;
import com.serviceflow.ai.intelligence.config.AiProperties;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.dto.AiAnalysisRequest;
import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import com.serviceflow.ai.intelligence.repository.AiAnalysisRepository;
import com.serviceflow.ai.intelligence.repository.AiSessionRepository;
import com.serviceflow.ai.intelligence.repository.AiSuggestionRepository;
import com.serviceflow.ai.intelligence.vo.AiAnalysisResponse;
import com.serviceflow.ai.knowledge.service.KnowledgeService;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.service.TicketSlaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 分析编排：逐单权限、工单上下文一致性、成功/失败落库、去重不重复调用模型。 */
class AiAnalysisServiceTest {

    private final AiTicketAccessGuard guard = mock(AiTicketAccessGuard.class);
    private final AiAnalysisRepository analysisRepository = mock(AiAnalysisRepository.class);
    private final AiSuggestionRepository suggestionRepository = mock(AiSuggestionRepository.class);
    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
    private final DispatchService dispatchService = mock(DispatchService.class);
    private final AgentSkillRepository skillRepository = mock(AgentSkillRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiProperties properties = new AiProperties();
    private final com.serviceflow.ai.intelligence.governance.service.AiRouteResolver routeResolver =
            mock(com.serviceflow.ai.intelligence.governance.service.AiRouteResolver.class);
    private final com.serviceflow.ai.intelligence.governance.service.AiQuotaService quotaService =
            mock(com.serviceflow.ai.intelligence.governance.service.AiQuotaService.class);
    private final com.serviceflow.ai.intelligence.governance.service.AiCallLogService callLogService =
            mock(com.serviceflow.ai.intelligence.governance.service.AiCallLogService.class);
    private final com.serviceflow.ai.common.audit.AuditLogService auditLogService =
            mock(com.serviceflow.ai.common.audit.AuditLogService.class);

    private final AiAnalysisService service = new AiAnalysisService(
            guard, analysisRepository, suggestionRepository, sessionRepository, client,
            knowledgeService, dispatchService, new TicketSlaService(), ticketRepository,
            skillRepository, new AiRequestLimiter(properties),
            new AiMapper(objectMapper), properties, objectMapper,
            routeResolver,
            new com.serviceflow.ai.intelligence.governance.security.AiPiiMasker(),
            quotaService, callLogService,
            new com.serviceflow.ai.intelligence.governance.service.AiCircuitBreaker(),
            auditLogService);

    private final LoginUser agent = new LoginUser(6L, "wangwu", "王五", UserRole.AGENT);

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(10L);
        t.setTicketNo("SF20261116-TEST");
        t.setTitle("VPN 无法连接");
        t.setDescription("用户反馈 VPN 拨号失败，提示 691");
        t.setType("NETWORK");
        t.setPriority("MEDIUM");
        t.setStatus("ASSIGNED");
        t.setCreator("张三");
        t.setCreatorId(9L);
        t.setAssignee("王五");
        t.setAssigneeId(6L);
        return t;
    }

    private void stubProcess(JsonNode result) {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(knowledgeService.promptCandidates(any(), anyInt())).thenReturn(List.of());
        when(skillRepository.findAll()).thenReturn(List.of());
        when(analysisRepository.insert(any())).thenAnswer(inv -> {
            AiAnalysis a = inv.getArgument(0);
            a.setId(5L);
            return a;
        });
        when(client.call(anyString(), anyMap())).thenReturn(
                objectMapper.createObjectNode()
                        .put("promptVersion", "ticket_analysis@v1")
                        .put("model", "deepseek-chat")
                        .set("result", result));
    }

    @Test
    void chatTypeIsRejectedOnAnalysisEndpoint() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.analyze(new AiAnalysisRequest(10L, "CHAT", null, "你好", true)));
        assertEquals("追问请使用会话消息接口", e.getMessage());
        verify(guard, never()).requireAccessible(any());
    }

    @Test
    void dispatchAssistanceRequiresAdmin() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        doThrow(new ForbiddenException("仅管理员可执行该操作")).when(guard).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> service.analyze(new AiAnalysisRequest(10L, "DISPATCH", null, null, true)));
        verify(client, never()).call(anyString(), anyMap());
    }

    @Test
    void analyzePersistsTicketContextAndSuggestion() {
        JsonNode result = objectMapper.createObjectNode()
                .put("recommendedType", "ACCOUNT")
                .put("typeConfidence", 0.88)
                .put("summary", "账号锁定导致 VPN 认证失败");
        stubProcess(result);
        when(suggestionRepository.findByAnalysisId(5L)).thenReturn(List.of());
        when(analysisRepository.findById(5L)).thenReturn(Optional.of(saved(result.toString())));

        AiAnalysisResponse response = service.analyze(
                new AiAnalysisRequest(10L, "TICKET_ANALYSIS", null, null, true));

        assertEquals(10L, response.ticketId());
        assertEquals("SUCCESS", response.status());
        // 送给模型的是真实工单上下文，而不是前端传入的任意文本
        ArgumentCaptor<Map> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(client).call(eq("/ai/ticket-analysis"), payloadCaptor.capture());
        Map<?, ?> body = payloadCaptor.getValue();
        Map<?, ?> requestPayload = (Map<?, ?>) body.get("payload");
        Map<?, ?> ticketPayload = (Map<?, ?>) requestPayload.get("ticket");
        assertEquals("SF20261116-TEST", ticketPayload.get("ticketNo"));
        verify(analysisRepository).markSuccess(eq(5L), eq("ticket_analysis@v1"),
                eq("deepseek-chat"), anyInt(), anyString());
        ArgumentCaptor<AiSuggestion> suggestionCaptor = ArgumentCaptor.forClass(AiSuggestion.class);
        verify(suggestionRepository).insert(suggestionCaptor.capture());
        assertEquals("CLASSIFICATION", suggestionCaptor.getValue().getSuggestionType());
        assertEquals(5L, suggestionCaptor.getValue().getAnalysisId());
        assertEquals(10L, suggestionCaptor.getValue().getTicketId());
    }

    @Test
    void modelFailureIsPersistedAsFailedWithoutFakeResult() {
        stubProcess(objectMapper.createObjectNode());
        when(client.call(anyString(), anyMap()))
                .thenThrow(new AiServiceException("AI_TIMEOUT", "AI 服务响应超时，请稍后重试", 504));

        assertThrows(AiServiceException.class,
                () -> service.analyze(new AiAnalysisRequest(10L, "PRIORITY", null, null, true)));

        verify(analysisRepository).markFailed(eq(5L), eq("AI_TIMEOUT"), anyString(), anyInt());
        verify(suggestionRepository, never()).insert(any());
    }

    @Test
    void duplicateAnalysisWithinWindowReturnsCachedWithoutModelCall() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        AiAnalysis cached = saved("{\"keywords\":[],\"summary\":\"缓存结果\"}");
        when(analysisRepository.findRecentSuccess(eq(10L), eq("TICKET_ANALYSIS"), eq(6L), any()))
                .thenReturn(Optional.of(cached));
        when(suggestionRepository.findByAnalysisId(5L)).thenReturn(List.of());

        AiAnalysisResponse response = service.analyze(
                new AiAnalysisRequest(10L, "TICKET_ANALYSIS", null, null, false));

        assertEquals("缓存结果", response.result().path("summary").asText());
        verify(client, never()).call(anyString(), anyMap());
    }

    @Test
    void forceRefreshSkipsDedupe() {
        JsonNode result = objectMapper.createObjectNode().put("recommendedPriority", "HIGH")
                .put("suggestedResponseHours", 8);
        stubProcess(result);
        when(suggestionRepository.findByAnalysisId(5L)).thenReturn(List.of());
        when(analysisRepository.findById(5L)).thenReturn(Optional.of(saved(result.toString())));

        service.analyze(new AiAnalysisRequest(10L, "PRIORITY", null, null, true));

        verify(analysisRepository, never()).findRecentSuccess(any(), anyString(), any(), any());
        verify(client).call(eq("/ai/priority-recommendation"), anyMap());
    }

    private AiAnalysis saved(String resultJson) {
        AiAnalysis a = new AiAnalysis();
        a.setId(5L);
        a.setTicketId(10L);
        a.setAnalysisType("TICKET_ANALYSIS");
        a.setStatus("SUCCESS");
        a.setRequestedBy(6L);
        a.setResultJson(resultJson);
        a.setPromptVersion("ticket_analysis@v1");
        a.setModel("deepseek-chat");
        return a;
    }
}
