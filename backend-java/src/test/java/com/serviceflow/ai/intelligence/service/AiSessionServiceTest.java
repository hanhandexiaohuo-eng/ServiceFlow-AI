package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import com.serviceflow.ai.intelligence.entity.AiMessage;
import com.serviceflow.ai.intelligence.entity.AiSession;
import com.serviceflow.ai.intelligence.repository.AiAnalysisRepository;
import com.serviceflow.ai.intelligence.repository.AiMessageRepository;
import com.serviceflow.ai.intelligence.repository.AiSessionRepository;
import com.serviceflow.ai.intelligence.vo.AiAnalysisResponse;
import com.serviceflow.ai.intelligence.vo.AiChatResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 会话归属校验、归档保护、追问失败不写假回复。 */
class AiSessionServiceTest {

    private final AiSessionRepository sessionRepository = mock(AiSessionRepository.class);
    private final AiMessageRepository messageRepository = mock(AiMessageRepository.class);
    private final AiAnalysisRepository analysisRepository = mock(AiAnalysisRepository.class);
    private final AiTicketAccessGuard guard = mock(AiTicketAccessGuard.class);
    private final AiAnalysisService analysisService = mock(AiAnalysisService.class);
    private final AiPropertiesHolder holder = new AiPropertiesHolder();
    private final AiSessionService service = new AiSessionService(
            sessionRepository, messageRepository, analysisRepository, guard,
            analysisService, holder.limiter, new AiMapper(new ObjectMapper()));

    private final LoginUser agent = new LoginUser(6L, "wangwu", "王五", UserRole.AGENT);

    private static final class AiPropertiesHolder {
        private final com.serviceflow.ai.intelligence.config.AiProperties properties =
                new com.serviceflow.ai.intelligence.config.AiProperties();
        private final AiRequestLimiter limiter = new AiRequestLimiter(properties);
    }

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(10L);
        t.setTicketNo("SF20261116-TEST");
        t.setTitle("VPN 无法连接");
        t.setCreator("张三");
        t.setCreatorId(9L);
        t.setAssignee("王五");
        t.setAssigneeId(6L);
        return t;
    }

    private AiSession session(long userId, String status) {
        AiSession s = new AiSession();
        s.setId(1L);
        s.setTicketId(10L);
        s.setUserId(userId);
        s.setTitle("VPN 会话");
        s.setStatus(status);
        return s;
    }

    private AiAnalysisResponse emptyResponse() {
        return new AiAnalysisResponse(7L, 10L, "SF20261116-TEST", "CHAT", "SUCCESS", 6L,
                "王五", "ticket_qa@v1", "deepseek-chat", 120, null, null,
                null, null, null, null, List.of(), List.of());
    }

    @Test
    void archivedSessionCannotBeAsked() {
        when(guard.currentUser()).thenReturn(agent);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(6L, "ARCHIVED")));
        when(guard.requireAccessible(10L)).thenReturn(ticket());

        assertThrows(ConflictException.class, () -> service.ask(1L, "还能追问吗"));
        verifyNoInteractions(messageRepository, analysisService);
    }

    @Test
    void otherUsersSessionIsForbidden() {
        when(guard.currentUser()).thenReturn(agent);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(99L, "ACTIVE")));

        assertThrows(ForbiddenException.class, () -> service.ask(1L, "越权访问"));
        verify(guard, never()).requireAccessible(any());
        verifyNoInteractions(messageRepository, analysisService);
    }

    @Test
    void askWritesUserAndAssistantMessagesUsingModelAnswer() {
        when(guard.currentUser()).thenReturn(agent);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(6L, "ACTIVE")));
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        doAnswer(inv -> {
            AiMessage m = inv.getArgument(0);
            m.setId("USER".equals(m.getRole()) ? 1L : 2L);
            return null;
        }).when(messageRepository).insert(any());
        AiAnalysis analysis = new AiAnalysis();
        analysis.setId(7L);
        analysis.setStatus("SUCCESS");
        analysis.setResultJson("{\"answer\":\"请先在账号中心重置密码\"}");
        when(analysisService.execute(any(), any(), eq(1L), any())).thenReturn(analysis);
        when(analysisService.toResponse(analysis)).thenReturn(emptyResponse());
        AiMessage userMessage = new AiMessage();
        userMessage.setId(1L);
        userMessage.setSessionId(1L);
        userMessage.setRole("USER");
        userMessage.setContent("怎么处理");
        AiMessage assistantMessage = new AiMessage();
        assistantMessage.setId(2L);
        assistantMessage.setSessionId(1L);
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setContent("请先在账号中心重置密码");
        assistantMessage.setAnalysisId(7L);
        when(messageRepository.findById(1L)).thenReturn(userMessage);
        when(messageRepository.findById(2L)).thenReturn(assistantMessage);

        AiChatResponse response = service.ask(1L, "怎么处理");

        assertNotNull(response.analysis());
        assertEquals("请先在账号中心重置密码", response.assistantMessage().content());
        assertEquals(7L, response.assistantMessage().analysisId());
        verify(sessionRepository).touch(1L);
    }

    @Test
    void modelFailureKeepsUserMessageButNeverWritesAssistant() {
        when(guard.currentUser()).thenReturn(agent);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session(6L, "ACTIVE")));
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(analysisService.execute(any(), any(), anyLong(), any()))
                .thenThrow(new com.serviceflow.ai.intelligence.client.AiServiceException(
                        "AI_UNAVAILABLE", "AI 服务暂时不可用", 502));

        assertThrows(com.serviceflow.ai.intelligence.client.AiServiceException.class,
                () -> service.ask(1L, "怎么处理"));

        verify(messageRepository).insert(any()); // 仅 USER 一条
        verify(sessionRepository, never()).touch(anyLong());
    }
}
