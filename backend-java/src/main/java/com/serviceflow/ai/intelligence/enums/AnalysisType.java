package com.serviceflow.ai.intelligence.enums;

/** AI 分析类型：对应 Python 服务的 6 个 Prompt。 */
public enum AnalysisType {
    TICKET_ANALYSIS,
    PRIORITY,
    SOLUTION,
    DISPATCH,
    CHAT;

    public static AnalysisType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的 AI 分析类型：" + value);
        }
    }

    public boolean isVisibleToUser() {
        return this != CHAT;
    }
}
