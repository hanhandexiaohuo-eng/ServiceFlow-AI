package com.serviceflow.ai.intelligence.vo;

/** 会话追问结果：用户消息、AI 回复与分析记录。 */
public record AiChatResponse(
        AiMessageResponse userMessage,
        AiMessageResponse assistantMessage,
        AiAnalysisResponse analysis
) {
}
