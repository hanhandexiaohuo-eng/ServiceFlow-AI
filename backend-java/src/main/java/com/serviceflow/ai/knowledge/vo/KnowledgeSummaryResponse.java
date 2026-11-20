package com.serviceflow.ai.knowledge.vo;

import java.time.LocalDateTime;

/** 知识列表行。 */
public record KnowledgeSummaryResponse(
        Long id,
        String title,
        String category,
        String keywords,
        String status,
        String updatedByName,
        LocalDateTime updatedAt
) {
}
