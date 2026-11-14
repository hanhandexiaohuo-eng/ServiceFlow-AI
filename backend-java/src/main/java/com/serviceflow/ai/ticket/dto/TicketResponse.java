package com.serviceflow.ai.ticket.dto;

import com.serviceflow.ai.ticket.entity.Ticket;

import java.time.LocalDateTime;

/**
 * 工单响应。对外只暴露 DTO 不暴露 Entity：
 * 以后表结构变了（比如加内部字段），接口契约可以保持不变。
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
        String assignee,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
                t.getId(), t.getTicketNo(), t.getTitle(), t.getDescription(),
                t.getType(), t.getPriority(), t.getStatus(),
                t.getCreator(), t.getAssignee(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
