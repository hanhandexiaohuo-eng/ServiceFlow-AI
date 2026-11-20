package com.serviceflow.ai.ticket.dto;

import com.serviceflow.ai.ticket.entity.TicketEvent;

import java.time.LocalDateTime;

/** 工单事件响应：时间线、操作记录共用。 */
public record TicketEventResponse(
        Long id,
        String action,
        String fromStatus,
        String toStatus,
        Long operatorId,
        String operator,
        String note,
        LocalDateTime createdAt
) {
    public static TicketEventResponse from(TicketEvent e) {
        return new TicketEventResponse(
                e.getId(), e.getAction(), e.getFromStatus(), e.getToStatus(),
                e.getOperatorId(), e.getOperatorName(), e.getNote(), e.getCreatedAt());
    }
}
