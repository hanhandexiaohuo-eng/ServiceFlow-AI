package com.serviceflow.ai.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 将知识文章关联到工单。 */
public record KnowledgeLinkRequest(
        @NotNull(message = "工单 id 不能为空")
        Long ticketId,

        @Size(max = 255, message = "备注不能超过 255 字")
        String note
) {
}
