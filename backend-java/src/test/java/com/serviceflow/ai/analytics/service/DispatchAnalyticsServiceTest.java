package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.DispatchAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.DispatchAnalyticsRepository.DispatchDecisionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 调度效果：待确认排除在分母外、推荐人缺失单列 unverifiable、样本不足与负载变化不可用。 */
class DispatchAnalyticsServiceTest {

    private static final AnalyticsQuery QUERY =
            new AnalyticsQuery(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), null, null, null);
    private static final LocalDateTime RECOMMEND_AT = LocalDateTime.of(2026, 11, 10, 10, 0, 0);

    private final DispatchAnalyticsRepository repository = mock(DispatchAnalyticsRepository.class);
    private final AnalyticsSecurity security = mock(AnalyticsSecurity.class);
    private final DispatchAnalyticsService service = new DispatchAnalyticsService(repository, security);

    @BeforeEach
    void stubDetails() {
        when(repository.skillChecks(QUERY)).thenReturn(List.of());
    }

    @Test
    void pendingIsExcludedWhileUnverifiableIsCountedSeparately() {
        when(repository.recommendationCount(QUERY)).thenReturn(4L);
        when(repository.decisionRows(QUERY)).thenReturn(List.of(
                // 采纳：推荐人与分配人相同
                recommend(1L, 1L, "张三", "URGENT"),
                assign(1L, 1L, "张三", RECOMMEND_AT.plusSeconds(120)),
                // 拒绝：推荐人与分配人不同
                recommend(2L, 1L, "张三", "HIGH"),
                assign(2L, 2L, "李四", RECOMMEND_AT.plusSeconds(240)),
                // 待确认：推荐后没有 ASSIGN
                recommend(3L, 1L, "张三", "MEDIUM"),
                // 推荐人未记录但后续有 ASSIGN：不可验证，不计入决策与采纳/拒绝
                recommend(4L, null, null, "LOW"),
                assign(4L, 2L, "李四", RECOMMEND_AT.plusSeconds(60))));

        Map<String, Object> result = service.overview(QUERY);

        assertEquals(4L, result.get("recommendationCount"));
        assertEquals(1L, result.get("acceptedCount"));
        assertEquals(1L, result.get("rejectedCount"));
        assertEquals(1L, result.get("unverifiableCount"));
        assertEquals(1L, result.get("pendingCount"));
        // 已决策只含采纳 + 拒绝，待确认与不可验证都不进分母
        assertEquals(2L, result.get("decisionCount"));
        assertEquals(3L, result.get("assignedAfterRecommend"));
        assertEquals(50.0, result.get("adoptionRate"));
        assertEquals(50.0, result.get("rejectionRate"));
        // (120 + 240 + 60) / 3
        assertEquals(140.0, result.get("avgConfirmSeconds"));
        assertEquals(true, result.get("lowSample"));
        assertEquals(false, result.get("loadChangeAvailable"));

        List<Map<String, Object>> agents = asList(result.get("agents"));
        assertEquals(2, agents.size());
        Map<String, Object> known = agents.get(0);
        assertEquals(1L, known.get("agentId"));
        assertEquals(3L, known.get("recommendCount"));
        assertEquals(1L, known.get("acceptedCount"));
        assertEquals(1L, known.get("rejectedCount"));
        Map<String, Object> unknown = agents.get(1);
        assertEquals("未记录推荐人", unknown.get("agentName"));
        assertEquals(1L, unknown.get("recommendCount"));
        assertEquals(0L, unknown.get("acceptedCount"));
        assertEquals(0L, unknown.get("rejectedCount"));

        List<Map<String, Object>> priorities = asList(result.get("priorityEffects"));
        assertEquals(4, priorities.size());
        assertEquals(1L, priorities.get(0).get("decisionCount"));
        assertEquals(1L, priorities.get(0).get("acceptedCount"));
        assertEquals(100.0, priorities.get(0).get("adoptionRate"));
        assertEquals(1L, priorities.get(1).get("decisionCount"));
        assertEquals(0L, priorities.get(1).get("acceptedCount"));
        assertEquals(0.0, priorities.get(1).get("adoptionRate"));
        assertEquals(0L, priorities.get(2).get("decisionCount"));

        List<Map<String, Object>> trend = asList(result.get("adoptionTrend"));
        assertEquals(16, trend.size());
        Map<String, Object> day = trend.get(9);
        assertEquals("2026-11-10", day.get("date"));
        assertEquals(2L, day.get("decisionCount"));
        assertEquals(1L, day.get("acceptedCount"));
        assertEquals(50.0, day.get("rate"));
    }

    @Test
    void tenRecommendationsMarkSampleSufficient() {
        when(repository.recommendationCount(QUERY)).thenReturn(10L);
        when(repository.decisionRows(QUERY)).thenReturn(List.of(recommend(1L, 1L, "张三", "HIGH")));

        Map<String, Object> result = service.overview(QUERY);

        assertEquals(10L, result.get("recommendationCount"));
        assertEquals(false, result.get("lowSample"));
        assertEquals(1L, result.get("pendingCount"));
        assertEquals(0L, result.get("decisionCount"));
    }

    @Test
    void ticketRowsReportPendingDecisionAsNull() {
        when(repository.recommendationCount(QUERY)).thenReturn(1L);
        when(repository.decisionRows(QUERY)).thenReturn(List.of(recommend(1L, 1L, "张三", "HIGH")));

        List<Map<String, Object>> rows = service.ticketRows(QUERY);

        assertEquals(1, rows.size());
        assertEquals("SF1", rows.get(0).get("ticketNo"));
        assertEquals("HIGH", rows.get(0).get("priority"));
        // 待确认没有分配记录，分配人员为空
        assertNull(rows.get(0).get("agentName"));
        assertNull(rows.get(0).get("accepted"));
        assertNull(rows.get(0).get("confirmSeconds"));
        assertEquals("2026-11-10T10:00:00", rows.get(0).get("recommendAt"));
    }

    private static DispatchDecisionRow recommend(long ticketId, Long agentId, String agentName, String priority) {
        return new DispatchDecisionRow(ticketId, "SF" + ticketId, priority, RECOMMEND_AT,
                agentId, agentName, null, null, null);
    }

    private static DispatchDecisionRow assign(long ticketId, Long agentId, String agentName,
                                              LocalDateTime assignAt) {
        return new DispatchDecisionRow(ticketId, "SF" + ticketId, "HIGH", null,
                null, null, agentId, agentName, assignAt);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
