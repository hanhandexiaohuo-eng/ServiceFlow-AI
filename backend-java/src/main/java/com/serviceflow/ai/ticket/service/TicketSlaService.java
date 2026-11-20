package com.serviceflow.ai.ticket.service;

import com.serviceflow.ai.ticket.dto.SlaPolicyResponse;
import com.serviceflow.ai.ticket.dto.TicketSlaResponse;
import com.serviceflow.ai.ticket.entity.Ticket;
import com.serviceflow.ai.ticket.enums.Priority;
import com.serviceflow.ai.ticket.enums.SlaState;
import com.serviceflow.ai.ticket.enums.TicketStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 服务：策略、截止时间与状态评估。
 * 时限与预警阈值来自配置（sla.policy.*、sla.warning-minutes），默认值与 Phase 2 口径一致（4/8/24/72 小时、120 分钟）。
 * 截止时间持久化在工单上，前端不自行推算。
 */
@Service
public class TicketSlaService {

    /** 默认预警阈值，字段初始化值保证脱离 Spring 的单元测试仍按业务默认口径运行 */
    static final int DEFAULT_WARNING_MINUTES = 120;

    private static final List<Priority> POLICY_ORDER =
            List.of(Priority.URGENT, Priority.HIGH, Priority.MEDIUM, Priority.LOW);

    @Value("${sla.warning-minutes:120}")
    private int warningMinutes = DEFAULT_WARNING_MINUTES;

    @Value("${sla.policy.urgent-hours:4}")
    private int urgentHours = 4;

    @Value("${sla.policy.high-hours:8}")
    private int highHours = 8;

    @Value("${sla.policy.medium-hours:24}")
    private int mediumHours = 24;

    @Value("${sla.policy.low-hours:72}")
    private int lowHours = 72;

    public int hours(String priority) {
        return switch (parsePriority(priority)) {
            case URGENT -> urgentHours;
            case HIGH -> highHours;
            case MEDIUM -> mediumHours;
            case LOW -> lowHours;
        };
    }

    /** 当前生效的预警阈值（分钟） */
    public int warningMinutes() {
        return warningMinutes;
    }

    public String label(String priority) {
        return parsePriority(priority).name() + " 优先级 " + hours(priority) + " 小时响应";
    }

    public LocalDateTime dueAt(LocalDateTime createdAt, String priority) {
        return createdAt.plusHours(hours(priority));
    }

    public List<SlaPolicyResponse> policies() {
        return POLICY_ORDER.stream()
                .map(p -> new SlaPolicyResponse(p.name(), hours(p.name()), warningMinutes, label(p.name())))
                .toList();
    }

    /**
     * 评估工单 SLA。已完成/已取消停止计时；
     * 完成时间优先取 resolvedAt / closedAt / cancelledAt，保证「按时完成/超时完成」可追溯。
     */
    public TicketSlaResponse evaluate(Ticket t) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = t.getCreatedAt() == null ? now : t.getCreatedAt();
        LocalDateTime dueAt = t.getSlaDueAt() != null
                ? t.getSlaDueAt()
                : dueAt(createdAt, t.getPriority());
        TicketStatus status = TicketStatus.parse(t.getStatus());
        LocalDateTime completedAt = t.getResolvedAt() != null ? t.getResolvedAt()
                : t.getClosedAt() != null ? t.getClosedAt()
                : t.getCancelledAt();

        boolean completed = status == TicketStatus.RESOLVED
                || status == TicketStatus.CLOSED
                || status == TicketStatus.CANCELLED;
        LocalDateTime endAt = completed && completedAt != null ? completedAt : now;
        long remainingMinutes = ceilMinutes(endAt, dueAt);
        int elapsedPercent = elapsedPercent(createdAt, dueAt, endAt);

        SlaState state;
        if (completed) {
            state = SlaState.COMPLETED;
        } else if (remainingMinutes < 0) {
            state = SlaState.OVERDUE;
        } else if (remainingMinutes <= warningMinutes) {
            state = SlaState.WARNING;
        } else {
            state = SlaState.ON_TRACK;
        }

        return new TicketSlaResponse(
                label(t.getPriority()), hours(t.getPriority()), dueAt,
                state.name(), remainingMinutes, elapsedPercent,
                completed ? completedAt : null);
    }

    private long ceilMinutes(LocalDateTime from, LocalDateTime to) {
        long seconds = Duration.between(from, to).getSeconds();
        return (long) Math.ceil(seconds / 60.0);
    }

    private int elapsedPercent(LocalDateTime createdAt, LocalDateTime dueAt, LocalDateTime endAt) {
        long total = Duration.between(createdAt, dueAt).getSeconds();
        if (total <= 0) {
            return 100;
        }
        long elapsed = Duration.between(createdAt, endAt).getSeconds();
        long percent = Math.round(elapsed * 100.0 / total);
        return (int) Math.max(0, Math.min(100, percent));
    }

    private Priority parsePriority(String priority) {
        try {
            return Priority.valueOf(priority.trim().toUpperCase());
        } catch (Exception e) {
            return Priority.MEDIUM;
        }
    }
}
