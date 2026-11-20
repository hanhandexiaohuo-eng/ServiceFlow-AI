package com.serviceflow.ai.intelligence.vo;

import java.time.LocalDateTime;

/** AI 会话列表行。 */
public record AiSessionResponse(
        Long id,
        Long ticketId,
        String ticketNo,
        String ticketTitle,
        Long userId,
        String userName,
        String title,
        String status,
        Integer messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
