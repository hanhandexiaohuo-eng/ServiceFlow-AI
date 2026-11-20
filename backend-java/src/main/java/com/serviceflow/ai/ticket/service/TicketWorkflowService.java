package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.auth.context.LoginUser;
import com.serviceflow.ai.auth.context.UserContext;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.service.UserQueryService;
import com.serviceflow.ai.common.exception.ConflictException;
import com.serviceflow.ai.common.exception.NotFoundException;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.entity.TicketEvent;
import com.serviceflow.ai.ticket.enums.TicketAction;
import com.serviceflow.ai.ticket.enums.TicketEventType;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import com.serviceflow.ai.ticket.repository.TicketEventRepository;
import com.serviceflow.ai.ticket.repository.TicketRepository;
import com.serviceflow.ai.ticket.workflow.TicketWorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 工作流执行层：校验动作 → 条件更新 → 写事件。
 * 所有状态变更都在事务内完成，且必须通过 TicketWorkflowEngine 的规则。
 */
@Service
public class TicketWorkflowService {

    private static final int NOTE_MAX = 500;

    private final TicketRepository ticketRepository;
    private final TicketEventRepository eventRepository;
    private final TicketWorkflowEngine engine;
    private final UserQueryService userQueryService;

    public TicketWorkflowService(TicketRepository ticketRepository,
                                 TicketEventRepository eventRepository,
                                 TicketWorkflowEngine engine,
                                 UserQueryService userQueryService) {
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.engine = engine;
        this.userQueryService = userQueryService;
    }

    @Transactional
    public Ticket assign(Long id, String assignee) {
        User target = userQueryService.requireActiveAssigneeByName(assignee);
        return transition(id, TicketAction.ASSIGN, t -> {
            t.setAssignee(target.getName());
            t.setAssigneeId(target.getId());
        }, TicketEventType.ASSIGN, "分配给 " + target.getName());
    }

    @Transactional
    public Ticket transfer(Long id, String assignee) {
        User target = userQueryService.requireActiveAssigneeByName(assignee);
        return transition(id, TicketAction.TRANSFER, t -> {
            t.setAssignee(target.getName());
            t.setAssigneeId(target.getId());
        }, TicketEventType.TRANSFER, "转交给 " + target.getName());
    }

    @Transactional
    public Ticket accept(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return transition(id, TicketAction.ACCEPT, t -> {
            if (t.getFirstResponseAt() == null) {
                t.setFirstResponseAt(now);
            }
        }, TicketEventType.ACCEPT, "处理人已接单");
    }

    @Transactional
    public Ticket start(Long id) {
        return transition(id, TicketAction.START, t -> {
        }, TicketEventType.START, "开始处理");
    }

    @Transactional
    public Ticket resolve(Long id, String resolution) {
        LocalDateTime now = LocalDateTime.now();
        String text = trimToNull(resolution);
        return transition(id, TicketAction.RESOLVE, t -> {
            t.setResolvedAt(now);
            t.setResolution(text);
        }, TicketEventType.RESOLVE, text == null ? "问题已解决" : text);
    }

    @Transactional
    public Ticket close(Long id, String note) {
        LocalDateTime now = LocalDateTime.now();
        String text = trimToNull(note);
        return transition(id, TicketAction.CLOSE, t -> t.setClosedAt(now),
                TicketEventType.CLOSE, text == null ? "确认关闭" : text);
    }

    @Transactional
    public Ticket cancel(Long id, String reason) {
        LocalDateTime now = LocalDateTime.now();
        String text = trimToNull(reason);
        return transition(id, TicketAction.CANCEL, t -> {
            t.setCancelledAt(now);
            t.setCancelReason(text);
        }, TicketEventType.CANCEL, text == null ? "取消工单" : text);
    }

    private Ticket transition(Long id, TicketAction action, Consumer<Ticket> mutator,
                              TicketEventType eventType, String note) {
        Ticket ticket = requireTicket(id);
        LoginUser operator = UserContext.getRequired();
        engine.assertAllowed(ticket, action, operator);

        TicketStatus from = TicketStatus.parse(ticket.getStatus());
        TicketStatus to = engine.targetStatus(from, action);
        mutator.accept(ticket);
        ticket.setStatus(to.name());

        int affected = ticketRepository.updateWorkflowState(ticket, from.name());
        if (affected == 0) {
            throw new ConflictException("工单状态已变更，请刷新后重试");
        }
        eventRepository.insert(event(ticket, eventType, from, to, operator, note));
        return requireTicket(id);
    }

    private Ticket requireTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("工单不存在：id=" + id));
    }

    private TicketEvent event(Ticket ticket, TicketEventType type, TicketStatus from, TicketStatus to,
                              LoginUser operator, String note) {
        TicketEvent e = new TicketEvent();
        e.setTicketId(ticket.getId());
        e.setAction(type.name());
        e.setFromStatus(from == null ? null : from.name());
        e.setToStatus(to == null ? null : to.name());
        e.setOperatorId(operator.id());
        e.setOperatorName(operator.name());
        e.setNote(note != null && note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
