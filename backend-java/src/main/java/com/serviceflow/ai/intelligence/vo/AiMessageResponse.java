package com.serviceflow.ai.intelligence.vo;

import java.time.LocalDateTime;

/** AI 会话消息。 */
public record AiMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String content,
        Long analysisId,
        LocalDateTime createdAt
) {
}
