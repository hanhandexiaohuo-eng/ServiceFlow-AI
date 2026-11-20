package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.enums.UserRole;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import com.serviceflow.ai.intelligence.repository.AiSuggestionRepository;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.workflow.TicketWorkflowEngine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 人工确认闭环：鉴权 → 原子占用 → 复用业务接口 → 失败回滚。 */
class AiSuggestionServiceTest {

    private final AiSuggestionRepository repository = mock(AiSuggestionRepository.class);
    private final AiTicketAccessGuard guard = mock(AiTicketAccessGuard.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final TicketWorkflowEngine workflowEngine = mock(TicketWorkflowEngine.class);
    private final AiMapper mapper = new AiMapper(new ObjectMapper());
    private final AiSuggestionService service = new AiSuggestionService(
            repository, guard, ticketService, workflowEngine, mapper);

    private final LoginUser agent = new LoginUser(6L, "wangwu", "王五", UserRole.AGENT);

    private Ticket ticket() {
        Ticket t = new Ticket();
        t.setId(10L);
        t.setTicketNo("SF20261116-AAAA");
        t.setTitle("VPN 无法连接");
        t.setDescription("用户反馈 VPN 拨号失败");
        t.setType("NETWORK");
        t.setPriority("MEDIUM");
        t.setStatus("ASSIGNED");
        t.setAssignee("王五");
        t.setAssigneeId(6L);
        t.setCreator("张三");
        t.setCreatorId(9L);
        return t;
    }

    private AiSuggestion suggestion(String type, String payload) {
        AiSuggestion s = new AiSuggestion();
        s.setId(1L);
        s.setAnalysisId(2L);
        s.setTicketId(10L);
        s.setSuggestionType(type);
        s.setStatus("PENDING");
        s.setPayload(payload);
        s.setSummary("摘要");
        s.setCreatedBy(6L);
        return s;
    }

    @Test
    void acceptPriorityUpdatesTicketThroughServiceAndMarksExecuted() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(workflowEngine.canEdit(any(), eq(agent))).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("PRIORITY", "{\"recommendedPriority\":\"HIGH\"}")));
        when(repository.claim(eq(1L), eq(6L), any())).thenReturn(1);

        service.accept(1L, "同意调整");

        ArgumentCaptor<TicketUpdateRequest> captor = ArgumentCaptor.forClass(TicketUpdateRequest.class);
        verify(ticketService).update(eq(10L), captor.capture());
        assertEquals("HIGH", captor.getValue().priority());
        assertEquals("NETWORK", captor.getValue().type());
        verify(repository).markExecuted(eq(1L), anyString());
    }

    @Test
    void acceptClassificationKeepsPriorityAndChangesType() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(workflowEngine.canEdit(any(), eq(agent))).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("CLASSIFICATION", "{\"recommendedType\":\"ACCOUNT\"}")));
        when(repository.claim(eq(1L), eq(6L), any())).thenReturn(1);

        service.accept(1L, null);

        ArgumentCaptor<TicketUpdateRequest> captor = ArgumentCaptor.forClass(TicketUpdateRequest.class);
        verify(ticketService).update(eq(10L), captor.capture());
        assertEquals("ACCOUNT", captor.getValue().type());
        assertEquals("MEDIUM", captor.getValue().priority());
    }

    @Test
    void acceptSolutionDoesNotTouchTicket() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("SOLUTION", "{\"problemAnalysis\":\"DNS 配置错误\"}")));
        when(repository.claim(eq(1L), eq(6L), any())).thenReturn(1);

        service.accept(1L, null);

        verifyNoInteractions(ticketService);
        verify(repository).markAccepted(eq(1L), anyString());
    }

    @Test
    void duplicateSubmissionReturnsConflictAndNeverWritesTicket() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(workflowEngine.canEdit(any(), eq(agent))).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("PRIORITY", "{\"recommendedPriority\":\"HIGH\"}")));
        when(repository.claim(eq(1L), eq(6L), any())).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.accept(1L, null));
        verify(ticketService, never()).update(anyLong(), any());
    }

    @Test
    void nonEditableTicketIsRejectedBeforeClaim() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("PRIORITY", "{\"recommendedPriority\":\"HIGH\"}")));
        when(workflowEngine.canEdit(any(), eq(agent))).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.accept(1L, null));
        verify(repository, never()).claim(anyLong(), anyLong(), any());
        verifyNoInteractions(ticketService);
    }

    @Test
    void businessFailureRevertsSuggestionToPending() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(workflowEngine.canEdit(any(), eq(agent))).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("PRIORITY", "{\"recommendedPriority\":\"HIGH\"}")));
        when(repository.claim(eq(1L), eq(6L), any())).thenReturn(1);
        when(ticketService.update(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("已结束工单不可编辑"));

        assertThrows(IllegalArgumentException.class, () -> service.accept(1L, null));
        verify(repository).revertToPending(1L);
    }

    @Test
    void rejectAlreadyHandledReturnsConflict() {
        when(guard.currentUser()).thenReturn(agent);
        when(guard.requireAccessible(10L)).thenReturn(ticket());
        when(repository.findById(1L)).thenReturn(Optional.of(
                suggestion("SOLUTION", "{}")));
        when(repository.reject(eq(1L), eq(6L), any())).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.reject(1L, "不需要"));
    }
}
