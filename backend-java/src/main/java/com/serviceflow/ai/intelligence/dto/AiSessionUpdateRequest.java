package com.serviceflow.ai.intelligence.dto;

import jakarta.validation.constraints.Size;

/** 更新会话标题或归档。 */
public record AiSessionUpdateRequest(
        @Size(max = 120, message = "会话标题不能超过 120 字")
        String title,

        String status
) {
}
