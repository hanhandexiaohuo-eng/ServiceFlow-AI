package com.serviceflow.ai.knowledge.vo;

import java.time.LocalDateTime;

/** 工单关联的知识文章。 */
public record KnowledgeLinkResponse(
        Long articleId,
        String title,
        String category,
        String keywords,
        String status,
        LocalDateTime linkedAt,
        String linkedByName,
        String note
) {
}
