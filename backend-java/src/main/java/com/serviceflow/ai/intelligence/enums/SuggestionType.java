package com.serviceflow.ai.intelligence.enums;

/** AI 建议类型：只有分类与优先级会触达工单内容。 */
public enum SuggestionType {
    CLASSIFICATION,
    PRIORITY,
    SOLUTION;

    public static SuggestionType parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的 AI 建议类型：" + value);
        }
    }

    public boolean appliesToTicket() {
        return this == CLASSIFICATION || this == PRIORITY;
    }
}
