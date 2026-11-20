package com.serviceflow.ai.knowledge.vo;

import java.util.List;

/** 知识推荐：必须能追溯到真实 kb_articles 记录。 */
public record KnowledgeRecommendationResponse(
        Long articleId,
        String title,
        String category,
        String summary,
        String solutionPreview,
        String keywords,
        int relevance,
        String reason,
        List<String> matchedKeywords
) {
}
