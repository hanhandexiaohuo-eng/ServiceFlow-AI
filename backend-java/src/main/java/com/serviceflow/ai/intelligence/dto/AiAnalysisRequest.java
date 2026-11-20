package com.serviceflow.ai.intelligence.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 发起 AI 分析。DISPATCH 仅管理员可用，校验在服务端完成。 */
public record AiAnalysisRequest(
        @NotNull(message = "工单 id 不能为空")
        Long ticketId,

        @NotNull(message = "分析类型不能为空")
        String type,

        Long sessionId,

        @Size(max = 2000, message = "追问内容不能超过 2000 字")
        String question,

        Boolean force
) {
    public boolean forceRefresh() {
        return Boolean.TRUE.equals(force);
    }
}
