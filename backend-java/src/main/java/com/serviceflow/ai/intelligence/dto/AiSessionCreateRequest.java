package com.serviceflow.ai.intelligence.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建 AI 会话。 */
public record AiSessionCreateRequest(
        @NotNull(message = "工单 id 不能为空")
        Long ticketId,

        @Size(max = 120, message = "会话标题不能超过 120 字")
        String title
) {
}
