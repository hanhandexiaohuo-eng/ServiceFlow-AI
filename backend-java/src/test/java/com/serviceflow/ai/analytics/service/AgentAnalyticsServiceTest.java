package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AgentAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AgentAnalyticsRepository.AgentStatRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 人员绩效：完成率分母 0、低样本排名、综合分四项权重与归一化、负载缺失。 */
class AgentAnalyticsServiceTest {

    private static final AnalyticsQuery QUERY =
            new AnalyticsQuery(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 16), null, null, null);

    private final AgentAnalyticsRepository repository = mock(AgentAnalyticsRepository.class);
    private final AnalyticsSecurity security = mock(AnalyticsSecurity.class);
    private final AgentAnalyticsService service = new AgentAnalyticsService(repository, security);

    @Test
    void completionAndSlaRateAreNullWhenDenominatorIsZero() {
        when(repository.agentStats(QUERY)).thenReturn(List.of(
                row(1L, "张三", 5, 0, 0, 0, 0, 0, 0)));

        Map<String, Object> result = service.performance(QUERY);
        Map<String, Object> agent = firstAgent(result);

        assertNull(agent.get("completionRate"));
        assertNull(agent.get("slaRate"));
        assertEquals(0L, result.get("maxWeightedResolved"));
        assertEquals(0.0, agent.get("volumeScore"));
        // 已配置最大并发时，0 占用 → 负载率 0、负载分 100
        assertEquals(0.0, agent.get("loadPercent"));
        assertEquals(100.0, agent.get("loadScore"));
    }

    @Test
    void compositeScoreUsesFourWeightsAndNormalizesVolume() {
        when(repository.agentStats(QUERY)).thenReturn(List.of(
                row(1L, "张三", 5, 10, 4, 2, 8, 10, 10)));

        Map<String, Object> agent = firstAgent(service.performance(QUERY));

        assertEquals(40.0, agent.get("completionRate"));
        assertEquals(80.0, agent.get("slaRate"));
        assertEquals(40.0, agent.get("loadPercent"));
        assertEquals(80.0, agent.get("slaScore"));
        assertEquals(40.0, agent.get("completionScore"));
        assertEquals(100.0, agent.get("volumeScore"));
        assertEquals(60.0, agent.get("loadScore"));
        // 0.5×80 + 0.2×40 + 0.2×100 + 0.1×60 = 74
        assertEquals(74.0, agent.get("score"));
        assertEquals(1, agent.get("rank"));
        assertEquals(false, agent.get("lowSample"));
    }

    @Test
    void lowSampleAgentIsUnrankedAndPlacedLast() {
        when(repository.agentStats(QUERY)).thenReturn(List.of(
                row(2L, "李四", 4, 2, 1, 1, 0, 0, 5),
                row(1L, "张三", 5, 10, 4, 2, 8, 10, 10)));

        Map<String, Object> result = service.performance(QUERY);
        List<Map<String, Object>> agents = asList(result.get("agents"));

        assertEquals(2, agents.size());
        Map<String, Object> valid = agents.get(0);
        assertEquals(1L, valid.get("agentId"));
        assertEquals(false, valid.get("lowSample"));
        assertEquals(1, valid.get("rank"));
        assertEquals(74.0, valid.get("score"));

        Map<String, Object> low = agents.get(1);
        assertEquals(2L, low.get("agentId"));
        assertEquals(true, low.get("lowSample"));
        assertNull(low.get("rank"));
        assertEquals(50.0, low.get("completionRate"));
        assertNull(low.get("slaRate"));
        // 5 / 10 → 50
        assertEquals(50.0, low.get("volumeScore"));
        // 0×0.5 + 50×0.2 + 50×0.2 + 75×0.1 = 27.5
        assertEquals(27.5, low.get("score"));
    }

    @Test
    void missingMaxConcurrentLeavesLoadPercentNull() {
        when(repository.agentStats(QUERY)).thenReturn(List.of(
                row(1L, "张三", null, 0, 0, 3, 0, 0, 0)));

        Map<String, Object> result = service.performance(QUERY);
        Map<String, Object> agent = firstAgent(result);

        assertNull(agent.get("maxConcurrent"));
        assertNull(agent.get("loadPercent"));
        assertEquals(0.0, agent.get("loadScore"));
        List<String> notes = asStringList(result.get("notes"));
        assertTrue(notes.contains("未配置最大并发，负载分计 0"));
    }

    private static AgentStatRow row(long id, String name, Integer maxConcurrent, long assigned,
                                    long resolved, long occupied, long slaNumerator,
                                    long slaDenominator, long weightedResolved) {
        return new AgentStatRow(id, name, name.equals("张三") ? "zhangsan" : "lisi", 1,
                maxConcurrent, assigned, resolved, occupied, 30.0, 120.0,
                slaNumerator, slaDenominator, 0, weightedResolved);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstAgent(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("agents")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        return (List<String>) value;
    }
}
