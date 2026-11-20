package com.serviceflow.ai.knowledge.vo;

import java.time.LocalDateTime;

/** 知识文章详情。 */
public record KnowledgeArticleResponse(
        Long id,
        String title,
        String category,
        String problem,
        String solution,
        String techStack,
        String keywords,
        Long sourceTicketId,
        String status,
        String createdByName,
        String updatedByName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
