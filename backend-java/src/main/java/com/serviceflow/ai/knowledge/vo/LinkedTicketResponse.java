package com.serviceflow.ai.knowledge.vo;

import java.time.LocalDateTime;

/** 知识文章上关联的工单。 */
public record LinkedTicketResponse(
        Long ticketId,
        String ticketNo,
        String title,
        String status,
        LocalDateTime linkedAt,
        String linkedByName,
        String note
) {
}
