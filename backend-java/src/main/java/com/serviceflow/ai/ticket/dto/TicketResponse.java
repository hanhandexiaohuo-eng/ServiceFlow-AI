package com.serviceflow.ai.ticket.dto;

import com.serviceflow.ai.ticket.entity.Ticket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单响应。SLA 与 allowedActions 均由服务端计算：
 * 前端只渲染后端允许的动作，服务端仍会在执行时二次校验。
 */
public record TicketResponse(
        Long id,
        String ticketNo,
        String title,
        String description,
        String type,
        String priority,
        String status,
        String creator,
        Long creatorId,
        String assignee,
        Long assigneeId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime firstResponseAt,
        LocalDateTime resolvedAt,
        LocalDateTime closedAt,
        LocalDateTime cancelledAt,
        TicketSlaResponse sla,
        List<String> allowedActions,
        boolean canEdit
) {
    public static TicketResponse from(Ticket t, TicketSlaResponse sla,
                                      List<String> allowedActions, boolean canEdit) {
        return new TicketResponse(
                t.getId(), t.getTicketNo(), t.getTitle(), t.getDescription(),
                t.getType(), t.getPriority(), t.getStatus(),
                t.getCreator(), t.getCreatorId(), t.getAssignee(), t.getAssigneeId(),
                t.getCreatedAt(), t.getUpdatedAt(),
                t.getFirstResponseAt(), t.getResolvedAt(), t.getClosedAt(), t.getCancelledAt(),
                sla, allowedActions, canEdit);
    }
}
