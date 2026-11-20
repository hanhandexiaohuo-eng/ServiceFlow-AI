package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.BucketCount;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.PrioritySlaStat;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.SlaAggregate;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.StatusCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SLA 分析：分子分母映射、分母 0 返回 null、缺失/预警/超时独立字段、桶与优先级补齐。 */
class SlaAnalyticsServiceTest {

    private static final AnalyticsQuery QUERY =
            new AnalyticsQuery(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), null, null, null);

    private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
    private final AnalyticsSecurity security = mock(AnalyticsSecurity.class);
    private final SlaAnalyticsService service = new SlaAnalyticsService(repository, security);

    @BeforeEach
    void stubEmptyDetails() {
        when(repository.slaStatusDistribution(QUERY)).thenReturn(List.of());
        when(repository.firstResponseBuckets(QUERY)).thenReturn(List.of());
        when(repository.resolutionBuckets(QUERY)).thenReturn(List.of());
        when(repository.slaPriorityComparison(QUERY)).thenReturn(List.of());
    }

    @Test
    void mapsAggregateNumeratorDenominatorAndRates() {
        when(repository.slaAggregate(QUERY)).thenReturn(
                new SlaAggregate(10, 8, 6, 3, 2, 1, 4, 7, 45.67, 120.44));

        Map<String, Object> result = service.overview(QUERY);
        Map<String, Object> firstResponse = asMap(result.get("firstResponse"));
        Map<String, Object> resolution = asMap(result.get("resolution"));

        assertEquals(8L, firstResponse.get("numerator"));
        assertEquals(10L, firstResponse.get("denominator"));
        assertEquals(80.0, firstResponse.get("rate"));

        assertEquals(3L, resolution.get("numerator"));
        assertEquals(6L, resolution.get("denominator"));
        assertEquals(50.0, resolution.get("rate"));
        assertEquals(true, resolution.get("proxy"));

        assertEquals(80.0, result.get("overallRate"));
        assertEquals(8L, result.get("overallNumerator"));
        assertEquals(10L, result.get("overallDenominator"));
        assertEquals(2L, result.get("warningCount"));
        assertEquals(1L, result.get("overdueCount"));
        assertEquals(4L, result.get("overdueCompleted"));
        assertEquals(7L, result.get("missingSla"));
        assertEquals(45.7, result.get("avgFirstResponseMinutes"));
        assertEquals(120.4, result.get("avgResolutionMinutes"));
        verify(security).requireViewer();
    }

    @Test
    void zeroDenominatorYieldsNullRateInsteadOfZero() {
        when(repository.slaAggregate(QUERY)).thenReturn(
                new SlaAggregate(0, 0, 0, 0, 0, 0, 0, 0, null, null));

        Map<String, Object> result = service.overview(QUERY);

        assertNull(asMap(result.get("firstResponse")).get("rate"));
        assertNull(asMap(result.get("resolution")).get("rate"));
        assertNull(result.get("overallRate"));
        assertNull(result.get("avgFirstResponseMinutes"));
        assertNull(result.get("avgResolutionMinutes"));
    }

    @Test
    void emptyRepositoryStillReturnsSixBucketsAndFourPriorities() {
        when(repository.slaAggregate(QUERY)).thenReturn(
                new SlaAggregate(0, 0, 0, 0, 0, 0, 0, 0, null, null));

        Map<String, Object> result = service.overview(QUERY);
        List<Map<String, Object>> firstResponseBuckets = asList(result.get("firstResponseBuckets"));
        List<Map<String, Object>> resolutionBuckets = asList(result.get("resolutionBuckets"));
        List<Map<String, Object>> priorities = asList(result.get("priorityComparison"));

        assertEquals(List.of("<60", "60-240", "240-480", "480-1440", "1440-4320", ">4320"),
                firstResponseBuckets.stream().map(item -> item.get("bucket")).toList());
        assertEquals(6, resolutionBuckets.size());
        firstResponseBuckets.forEach(item -> assertEquals(0L, item.get("count")));

        assertEquals(List.of("URGENT", "HIGH", "MEDIUM", "LOW"),
                priorities.stream().map(item -> item.get("priority")).toList());
        priorities.forEach(item -> {
            assertEquals(0L, item.get("denominator"));
            assertEquals(0L, item.get("numerator"));
            assertNull(item.get("rate"));
            assertNull(item.get("avgFirstResponseMinutes"));
            assertEquals(0L, item.get("overdue"));
        });
    }

    @Test
    void fillsStatusBucketsAndPriorityRowsFromRepository() {
        when(repository.slaAggregate(QUERY)).thenReturn(
                new SlaAggregate(10, 8, 0, 0, 0, 0, 0, 0, null, null));
        when(repository.slaStatusDistribution(QUERY)).thenReturn(
                List.of(new StatusCount("ON_TRACK", 5), new StatusCount("OVERDUE", 2)));
        when(repository.firstResponseBuckets(QUERY)).thenReturn(
                List.of(new BucketCount("<60", 3), new BucketCount(">4320", 1)));
        when(repository.slaPriorityComparison(QUERY)).thenReturn(
                List.of(new PrioritySlaStat("HIGH", 10, 8, 30.04, 2)));

        Map<String, Object> result = service.overview(QUERY);

        List<Map<String, Object>> states = asList(result.get("statusDistribution"));
        assertEquals(List.of("ON_TRACK", "WARNING", "OVERDUE", "COMPLETED"),
                states.stream().map(item -> item.get("state")).toList());
        assertEquals(5L, states.get(0).get("count"));
        assertEquals(0L, states.get(1).get("count"));
        assertEquals(2L, states.get(2).get("count"));
        assertEquals(0L, states.get(3).get("count"));

        List<Map<String, Object>> buckets = asList(result.get("firstResponseBuckets"));
        assertEquals(3L, buckets.get(0).get("count"));
        assertEquals(0L, buckets.get(1).get("count"));
        assertEquals(1L, buckets.get(5).get("count"));

        List<Map<String, Object>> priorities = asList(result.get("priorityComparison"));
        Map<String, Object> high = priorities.get(1);
        assertEquals("HIGH", high.get("priority"));
        assertEquals(10L, high.get("denominator"));
        assertEquals(8L, high.get("numerator"));
        assertEquals(80.0, high.get("rate"));
        assertEquals(30.0, high.get("avgFirstResponseMinutes"));
        assertEquals(2L, high.get("overdue"));

        Map<String, Object> urgent = priorities.get(0);
        assertEquals("URGENT", urgent.get("priority"));
        assertEquals(0L, urgent.get("denominator"));
        assertNull(urgent.get("rate"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
