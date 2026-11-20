package com.serviceflow.ai.intelligence.vo;

import java.util.List;

/** 会话详情：基础信息 + 消息 + 相关分析摘要。 */
public record AiSessionDetailResponse(
        AiSessionResponse session,
        List<AiMessageResponse> messages,
        List<AiAnalysisBrief> analyses
) {
}
