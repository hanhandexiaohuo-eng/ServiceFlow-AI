package com.serviceflow.ai.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新增/编辑知识文章。 */
public record KnowledgeSaveRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题不能超过 200 字")
        String title,

        @NotBlank(message = "分类不能为空")
        @Size(max = 32, message = "分类不能超过 32 字")
        String category,

        @NotBlank(message = "问题描述不能为空")
        String problem,

        @NotBlank(message = "解决方案不能为空")
        String solution,

        @Size(max = 200, message = "适用技术不能超过 200 字")
        String techStack,

        @NotBlank(message = "关键词不能为空")
        @Size(max = 255, message = "关键词不能超过 255 字")
        String keywords,

        Long sourceTicketId,

        String status
) {
}
