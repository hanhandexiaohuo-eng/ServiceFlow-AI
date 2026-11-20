package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiCallCounts;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiErrorStat;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.AiFeatureStat;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.SuggestionCounts;
import com.serviceflow.ai.analytics.repository.AiAnalyticsRepository.SuggestionTypeStat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI 效果：成功率排除 PENDING、采纳率排除 PENDING、已决策为 0 时返回 null。 */
class AiAnalyticsServiceTest {

    private static final AnalyticsQuery QUERY =
            new AnalyticsQuery(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), null, null, null);

    private final AiAnalyticsRepository repository = mock(AiAnalyticsRepository.class);
    private final AnalyticsSecurity security = mock(AnalyticsSecurity.class);
    private final AiAnalyticsService service = new AiAnalyticsService(repository, security);

    @BeforeEach
    void stubEmptyDetails() {
        when(repository.features(QUERY)).thenReturn(List.of());
        when(repository.errors(QUERY)).thenReturn(List.of());
        when(repository.suggestionByType(QUERY)).thenReturn(List.of());
    }

    @Test
    void successRateExcludesPendingCalls() {
        when(repository.callCounts(QUERY)).thenReturn(new AiCallCounts(15, 3, 1, 11, 100.04));
        when(repository.features(QUERY)).thenReturn(
                List.of(new AiFeatureStat("CLASSIFICATION", 8, 6, 2, 88.88)));
        when(repository.errors(QUERY)).thenReturn(List.of(new AiErrorStat("TIMEOUT", 3)));

        Map<String, Object> result = service.overview(QUERY);

        assertEquals(15L, result.get("totalCalls"));
        assertEquals(3L, result.get("successCount"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(11L, result.get("pendingCount"));
        // 3 / (3 + 1) = 75%，11 条 PENDING 不进分母
        assertEquals(75.0, result.get("successRate"));
        assertEquals(100.0, result.get("avgLatencyMs"));

        List<Map<String, Object>> features = asList(result.get("features"));
        assertEquals(1, features.size());
        assertEquals("CLASSIFICATION", features.get(0).get("analysisType"));
        assertEquals(8L, features.get(0).get("total"));
        assertEquals(75.0, features.get(0).get("successRate"));
        assertEquals(88.9, features.get(0).get("avgLatencyMs"));

        List<Map<String, Object>> errors = asList(result.get("errors"));
        assertEquals("TIMEOUT", errors.get(0).get("errorCode"));
        assertEquals(3L, errors.get(0).get("count"));
    }

    @Test
    void allPendingCallsYieldNullSuccessRate() {
        when(repository.callCounts(QUERY)).thenReturn(new AiCallCounts(5, 0, 0, 5, null));

        Map<String, Object> result = service.overview(QUERY);

        assertEquals(5L, result.get("pendingCount"));
        assertNull(result.get("successRate"));
    }

    @Test
    void adoptionRateUsesAcceptedPlusExecutedOverDecided() {
        when(repository.suggestionCounts(QUERY)).thenReturn(new SuggestionCounts(10, 4, 5, 1));
        when(repository.suggestionByType(QUERY)).thenReturn(
                List.of(new SuggestionTypeStat("SOLUTION", 5, 1, 2, 2)));

        Map<String, Object> result = service.suggestions(QUERY);

        assertEquals(10L, result.get("total"));
        assertEquals(4L, result.get("pending"));
        // 已采纳 5 = ACCEPTED + EXECUTED，已拒绝 1，已决策 6
        assertEquals(5L, result.get("accepted"));
        assertEquals(1L, result.get("rejected"));
        assertEquals(6L, result.get("decided"));
        assertEquals(83.3, result.get("adoptionRate"));

        List<Map<String, Object>> byType = asList(result.get("byType"));
        assertEquals(List.of("CLASSIFICATION", "PRIORITY", "SOLUTION"),
                byType.stream().map(item -> item.get("suggestionType")).toList());

        Map<String, Object> solution = byType.get(2);
        assertEquals(5L, solution.get("total"));
        assertEquals(1L, solution.get("pending"));
        assertEquals(2L, solution.get("accepted"));
        assertEquals(2L, solution.get("rejected"));
        assertEquals(4L, solution.get("decided"));
        assertEquals(50.0, solution.get("adoptionRate"));

        Map<String, Object> classification = byType.get(0);
        assertEquals(0L, classification.get("total"));
        assertEquals(0L, classification.get("decided"));
        assertNull(classification.get("adoptionRate"));
    }

    @Test
    void zeroDecidedSuggestionsYieldNullAdoptionRate() {
        when(repository.suggestionCounts(QUERY)).thenReturn(new SuggestionCounts(3, 3, 0, 0));

        Map<String, Object> result = service.suggestions(QUERY);

        assertEquals(0L, result.get("decided"));
        assertNull(result.get("adoptionRate"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
