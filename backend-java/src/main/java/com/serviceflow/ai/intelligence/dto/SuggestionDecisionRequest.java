package com.serviceflow.ai.intelligence.dto;

import jakarta.validation.constraints.Size;

/** 采纳或拒绝 AI 建议时的人工备注。 */
public record SuggestionDecisionRequest(
        @Size(max = 500, message = "备注不能超过 500 字")
        String note
) {
}
