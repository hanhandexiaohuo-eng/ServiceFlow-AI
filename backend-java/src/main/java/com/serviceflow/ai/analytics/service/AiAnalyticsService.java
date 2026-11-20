package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiCallCounts;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiErrorStat;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiFeatureStat;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.SuggestionCounts;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.SuggestionTypeStat;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 效果分析：调用成功率与建议采纳率是两个独立指标，不能互相替代。
 * 模型返回成功只代表调用链路成功，不代表建议内容正确，项目不计算「建议正确率」。
 */
@Service
public class AiAnalyticsService {

    private static final List<String> SUGGESTION_TYPES =
            List.of("CLASSIFICATION", "PRIORITY", "SOLUTION");

    private static final String INDEPENDENT_METRICS_NOTE =
            "调用成功率与建议采纳率是两个独立指标，模型返回成功不等于建议正确";

    private final AiAnalyticsRepository repository;
    private final AnalyticsSecurity security;

    public AiAnalyticsService(AiAnalyticsRepository repository, AnalyticsSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public Map<String, Object> overview(AnalyticsQuery query) {
        security.requireViewer();
        AiCallCounts counts = repository.callCounts(query);

        List<Map<String, Object>> features = new ArrayList<>();
        for (AiFeatureStat row : repository.features(query)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("analysisType", row.analysisType());
            item.put("total", row.total());
            item.put("success", row.successCount());
            item.put("failed", row.failedCount());
            item.put("successRate", percent(row.successCount(), row.successCount() + row.failedCount()));
            item.put("avgLatencyMs", roundOrNull(row.avgLatencyMs()));
            features.add(item);
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        for (AiErrorStat row : repository.errors(query)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("errorCode", row.errorCode());
            item.put("count", row.count());
            errors.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", counts.total());
        result.put("successCount", counts.successCount());
        result.put("failedCount", counts.failedCount());
        result.put("pendingCount", counts.pendingCount());
        result.put("successRate", percent(counts.successCount(),
                counts.successCount() + counts.failedCount()));
        result.put("avgLatencyMs", roundOrNull(counts.avgLatencyMs()));
        result.put("features", features);
        result.put("errors", errors);
        result.put("notes", List.of(
                "调用成功率 = SUCCESS / (SUCCESS + FAILED)，PENDING 不计入分母",
                INDEPENDENT_METRICS_NOTE,
                "平均响应耗时只统计 SUCCESS/FAILED 且 latency_ms 非空的调用",
                "错误类型只统计 FAILED 记录，error_code 为空归为 UNKNOWN"));
        return result;
    }

    public Map<String, Object> suggestions(AnalyticsQuery query) {
        security.requireViewer();
        SuggestionCounts counts = repository.suggestionCounts(query);
        long decided = counts.acceptedCount() + counts.rejectedCount();

        Map<String, SuggestionTypeStat> byTypeRows = new LinkedHashMap<>();
        for (SuggestionTypeStat row : repository.suggestionByType(query)) {
            byTypeRows.put(row.suggestionType(), row);
        }
        List<Map<String, Object>> byType = new ArrayList<>();
        for (String type : SUGGESTION_TYPES) {
            SuggestionTypeStat row = byTypeRows.get(type);
            long total = row == null ? 0L : row.total();
            long pending = row == null ? 0L : row.pendingCount();
            long accepted = row == null ? 0L : row.acceptedCount();
            long rejected = row == null ? 0L : row.rejectedCount();
            long typeDecided = accepted + rejected;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("suggestionType", type);
            item.put("total", total);
            item.put("pending", pending);
            item.put("accepted", accepted);
            item.put("rejected", rejected);
            item.put("decided", typeDecided);
            item.put("adoptionRate", percent(accepted, typeDecided));
            byType.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", counts.total());
        result.put("pending", counts.pendingCount());
        result.put("accepted", counts.acceptedCount());
        result.put("rejected", counts.rejectedCount());
        result.put("decided", decided);
        result.put("adoptionRate", percent(counts.acceptedCount(), decided));
        result.put("byType", byType);
        result.put("notes", List.of(
                "建议采纳率 = (ACCEPTED + EXECUTED) / 已决策，待确认 PENDING 不计入分母",
                "EXECUTED 由 ACCEPTED 流转而来，不重复计数",
                INDEPENDENT_METRICS_NOTE));
        return result;
    }

    private static Double percent(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return AnalyticsRepository.round1(numerator * 100.0 / denominator);
    }

    private static Double roundOrNull(Double value) {
        return value == null ? null : AnalyticsRepository.round1(value);
    }
}
