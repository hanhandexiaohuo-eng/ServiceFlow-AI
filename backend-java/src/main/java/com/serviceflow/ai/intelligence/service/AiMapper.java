package com.serviceflow.ai.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import com.serviceflow.ai.intelligence.entity.AiMessage;
import com.serviceflow.ai.intelligence.entity.AiSession;
import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import com.serviceflow.ai.intelligence.vo.AiAnalysisBrief;
import com.serviceflow.ai.intelligence.vo.AiMessageResponse;
import com.serviceflow.ai.intelligence.vo.AiSessionResponse;
import com.serviceflow.ai.intelligence.vo.AiSuggestionResponse;
import org.springframework.stereotype.Component;

/** 实体到响应的统一转换，避免各服务重复拼接展示字段。 */
@Component
public class AiMapper {

    private final ObjectMapper objectMapper;

    public AiMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return NullNode.getInstance();
        }
    }

    public AiSuggestionResponse toSuggestion(AiSuggestion s) {
        return new AiSuggestionResponse(
                s.getId(), s.getAnalysisId(), s.getTicketId(), s.getSuggestionType(), s.getStatus(),
                readJson(s.getPayload()), s.getSummary(), s.getCreatedBy(), s.getCreatedByName(),
                s.getDecidedBy(), s.getDecidedByName(), s.getDecidedAt(), s.getDecisionNote(),
                s.getExecutionNote(), s.getCreatedAt());
    }

    public AiSessionResponse toSession(AiSession s) {
        return new AiSessionResponse(
                s.getId(), s.getTicketId(), s.getTicketNo(), s.getTicketTitle(), s.getUserId(),
                s.getUserName(), s.getTitle(), s.getStatus(), s.getMessageCount(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    public AiMessageResponse toMessage(AiMessage m) {
        return new AiMessageResponse(
                m.getId(), m.getSessionId(), m.getRole(), m.getContent(), m.getAnalysisId(), m.getCreatedAt());
    }

    public AiAnalysisBrief toBrief(AiAnalysis a) {
        return new AiAnalysisBrief(
                a.getId(), a.getTicketId(), a.getTicketNo(), a.getAnalysisType(), a.getStatus(),
                summaryOf(a), a.getErrorCode(), a.getRequestedByName(), a.getCreatedAt());
    }

    /** 从结构化结果中提取列表摘要；失败时使用脱敏错误信息。 */
    public String summaryOf(AiAnalysis analysis) {
        if ("FAILED".equals(analysis.getStatus())) {
            return analysis.getErrorMessage() == null ? "AI 分析失败" : analysis.getErrorMessage();
        }
        JsonNode root = readJson(analysis.getResultJson());
        if (root.isMissingNode() || root.isNull()) {
            return "";
        }
        String type = analysis.getAnalysisType();
        return switch (type == null ? "" : type) {
            case "TICKET_ANALYSIS" -> text(root, "summary");
            case "PRIORITY" -> firstText(root, "reasons");
            case "SOLUTION" -> clip(text(root, "problemAnalysis"), 120);
            case "DISPATCH" -> {
                JsonNode assist = root.path("assist");
                String value = assist.isObject() ? text(assist, "summary") : text(root, "summary");
                yield clip(value, 120);
            }
            case "CHAT" -> clip(text(root, "answer"), 120);
            default -> "";
        };
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText();
    }

    private String firstText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).asText();
        }
        return "";
    }

    private String clip(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
