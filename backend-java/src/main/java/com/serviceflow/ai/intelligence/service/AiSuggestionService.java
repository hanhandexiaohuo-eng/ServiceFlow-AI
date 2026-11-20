package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.ForbiddenException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.intelligence.context.AiTicketAccessGuard;
import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import com.serviceflow.ai.intelligence.enums.SuggestionType;
import com.serviceflow.ai.intelligence.repository.AiSuggestionRepository;
import com.serviceflow.ai.intelligence.vo.AiSuggestionResponse;
import com.serviceflow.ai.ticket.dto.TicketUpdateRequest;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.service.TicketService;
import com.serviceflow.ai.ticket.workflow.TicketWorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工确认闭环：建议必须先被 claim（PENDING → ACCEPTED 原子更新）才允许执行，
 * 业务写入全部复用 TicketService.update，不绕过状态机、SLA 与编辑权限。
 */
@Service
public class AiSuggestionService {

    private final AiSuggestionRepository repository;
    private final AiTicketAccessGuard guard;
    private final TicketService ticketService;
    private final TicketWorkflowEngine workflowEngine;
    private final AiMapper mapper;

    public AiSuggestionService(AiSuggestionRepository repository,
                               AiTicketAccessGuard guard,
                               TicketService ticketService,
                               TicketWorkflowEngine workflowEngine,
                               AiMapper mapper) {
        this.repository = repository;
        this.guard = guard;
        this.ticketService = ticketService;
        this.workflowEngine = workflowEngine;
        this.mapper = mapper;
    }

    @Transactional
    public AiSuggestionResponse accept(Long id, String note) {
        AiSuggestion suggestion = requireSuggestion(id);
        Ticket ticket = guard.requireAccessible(suggestion.getTicketId());
        LoginUser user = guard.currentUser();
        SuggestionType type = SuggestionType.parse(suggestion.getSuggestionType());

        // 分类/优先级会写入工单，必须与普通编辑同样的权限与终态限制
        if (type.appliesToTicket() && !workflowEngine.canEdit(ticket, user)) {
            throw new ForbiddenException("仅管理员、创建人或当前处理人可采纳该建议，且工单不能已结束");
        }

        // 原子占用：并发/重复提交只有一次能成功
        int claimed = repository.claim(id, user.id(), note);
        if (claimed == 0) {
            throw new ConflictException("该建议已被处理，请刷新后重试");
        }

        try {
            String executionNote = applyToBusiness(suggestion, type, ticket, user);
            if (type == SuggestionType.SOLUTION) {
                repository.markAccepted(id, executionNote);
            } else {
                repository.markExecuted(id, executionNote);
            }
        } catch (RuntimeException e) {
            // 业务写入失败：释放占用，允许人工在修正后重试
            repository.revertToPending(id);
            throw e;
        }
        return mapper.toSuggestion(requireSuggestion(id));
    }

    @Transactional
    public AiSuggestionResponse reject(Long id, String note) {
        AiSuggestion suggestion = requireSuggestion(id);
        guard.requireAccessible(suggestion.getTicketId());
        int affected = repository.reject(id, guard.currentUser().id(), note);
        if (affected == 0) {
            throw new ConflictException("该建议已被处理，请刷新后重试");
        }
        return mapper.toSuggestion(requireSuggestion(id));
    }

    @Transactional(readOnly = true)
    public AiSuggestionResponse get(Long id) {
        AiSuggestion suggestion = requireSuggestion(id);
        guard.requireAccessible(suggestion.getTicketId());
        return mapper.toSuggestion(suggestion);
    }

    private String applyToBusiness(AiSuggestion suggestion, SuggestionType type, Ticket ticket, LoginUser user) {
        JsonNode payload = mapper.readJson(suggestion.getPayload());
        return switch (type) {
            case CLASSIFICATION -> {
                String recommendedType = payload.path("recommendedType").asText("");
                if (recommendedType.isBlank()) {
                    throw new IllegalStateException("建议内容缺少 recommendedType，无法执行");
                }
                ticketService.update(ticket.getId(), new TicketUpdateRequest(
                        ticket.getTitle(), ticket.getDescription(), recommendedType,
                        ticket.getPriority(), null, null));
                yield "已采纳：" + user.name() + " 将工单类型更新为 " + recommendedType;
            }
            case PRIORITY -> {
                String recommendedPriority = payload.path("recommendedPriority").asText("");
                if (recommendedPriority.isBlank()) {
                    throw new IllegalStateException("建议内容缺少 recommendedPriority，无法执行");
                }
                ticketService.update(ticket.getId(), new TicketUpdateRequest(
                        ticket.getTitle(), ticket.getDescription(), ticket.getType(),
                        recommendedPriority, null, null));
                yield "已采纳：" + user.name() + " 将优先级更新为 " + recommendedPriority + "（SLA 由系统重算）";
            }
            case SOLUTION -> "已采纳处理方案，由人工按方案执行";
        };
    }

    private AiSuggestion requireSuggestion(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("AI 建议不存在：id=" + id));
    }
}
