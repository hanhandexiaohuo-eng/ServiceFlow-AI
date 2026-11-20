package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.controller.ReportController;
import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 综合报表：5 类报表、指标取值来源、CSV 结构与 BOM、JSON 与 CSV 数值一致、非法类型。 */
class ReportServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 16);
    private static final AnalyticsQuery QUERY = new AnalyticsQuery(START, END, null, null, null);

    private final TicketAnalyticsService ticketAnalyticsService = mock(TicketAnalyticsService.class);
    private final SlaAnalyticsService slaAnalyticsService = mock(SlaAnalyticsService.class);
    private final AgentAnalyticsService agentAnalyticsService = mock(AgentAnalyticsService.class);
    private final DispatchAnalyticsService dispatchAnalyticsService = mock(DispatchAnalyticsService.class);
    private final AiAnalyticsService aiAnalyticsService = mock(AiAnalyticsService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AnalyticsSecurity security = mock(AnalyticsSecurity.class);
    private final ReportService reportService = new ReportService(ticketAnalyticsService,
            slaAnalyticsService, agentAnalyticsService, dispatchAnalyticsService, aiAnalyticsService,
            userRepository, security);

    @BeforeEach
    void stubServices() {
        when(ticketAnalyticsService.overview(any())).thenReturn(ticketOverview());
        when(ticketAnalyticsService.trend(any())).thenReturn(Map.of("points",
                List.of(point("2026-11-01", 10L, 5L, 3L))));
        when(slaAnalyticsService.overview(any())).thenReturn(slaOverview());
        when(agentAnalyticsService.performance(any())).thenReturn(agentPerformance());
        when(dispatchAnalyticsService.overview(any())).thenReturn(dispatchOverview());
        when(dispatchAnalyticsService.ticketRows(any())).thenReturn(List.of(dispatchRow()));
        when(aiAnalyticsService.overview(any())).thenReturn(aiOverview());
        when(aiAnalyticsService.suggestions(any())).thenReturn(aiSuggestions());
    }

    @Test
    void allFiveReportTypesProduceMetricsAndColumns() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("TICKET_OPERATIONS", "工单运营汇总");
        labels.put("SLA_EFFICIENCY", "SLA 效率汇总");
        labels.put("AGENT_PERFORMANCE", "人员绩效明细");
        labels.put("DISPATCH_EFFECT", "智能调度效果");
        labels.put("AI_EFFECT", "AI 效果汇总");

        labels.forEach((type, label) -> {
            Map<String, Object> report = reportService.build(type, QUERY);

            assertEquals(type, report.get("type"));
            assertEquals(label, report.get("reportName"));
            assertEquals("2026-11-01 ~ 2026-11-16", asMap(report.get("range")).get("label"));
            assertFalse(asList(report.get("metrics")).isEmpty(), type + " metrics 为空");
            assertFalse(asList(report.get("detailColumns")).isEmpty(), type + " detailColumns 为空");
            assertFalse(asList(report.get("detailRows")).isEmpty(), type + " detailRows 为空");
        });
    }

    @Test
    void ticketReportUsesServiceValues() {
        Map<String, Object> report = reportService.build("ticket_operations", QUERY);
        List<Map<String, Object>> metrics = asList(report.get("metrics"));

        assertEquals(42L, metricValue(metrics, "total"));
        assertEquals(30L, metricValue(metrics, "resolved"));
        assertEquals(20L, metricValue(metrics, "closed"));
        assertEquals(5L, metricValue(metrics, "processing"));
        assertEquals(2L, metricValue(metrics, "cancelled"));
        assertEquals(45.6, metricValue(metrics, "avgResolutionMinutes"));
        assertEquals(1, asList(report.get("detailRows")).size());
    }

    @Test
    void csvStartsWithBomAndContainsRequiredSections() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ReportController(reportService).export("TICKET_OPERATIONS", "2026-11-01", "2026-11-16",
                null, null, null, response);

        String csv = response.getContentAsString(StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("\uFEFF"), "CSV 必须以 UTF-8 BOM 开头");
        assertTrue(csv.contains("报表名称,工单运营汇总"));
        assertTrue(csv.contains("统计范围,2026-11-01 ~ 2026-11-16"));
        assertTrue(csv.contains("筛选条件,工单类型=全部; 优先级=全部; 处理人=全部"));
        assertTrue(csv.contains("生成时间,"));
        assertTrue(csv.contains("指标"));
        assertTrue(csv.contains("total,工单总数,42,"));
        assertTrue(csv.contains("字段说明"));
        assertTrue(csv.contains("date,日期"));
        assertTrue(csv.contains("date,created,resolved,closed"));
        assertTrue(csv.contains("2026-11-01,10,5,3"));
        assertTrue(response.getContentType().contains("text/csv"));
        assertTrue(response.getHeader("Content-Disposition").contains("phase6-ticket_operations-"));
        assertTrue(response.getHeader("Content-Disposition").endsWith(".csv\""));
    }

    @Test
    void csvMetricValuesMatchJsonBuild() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ReportController(reportService).export("TICKET_OPERATIONS", "2026-11-01", "2026-11-16",
                null, null, null, response);
        String csv = response.getContentAsString(StandardCharsets.UTF_8);

        List<Map<String, Object>> metrics = asList(reportService.build("TICKET_OPERATIONS", QUERY)
                .get("metrics"));
        for (Map<String, Object> metric : metrics) {
            Object value = metric.get("value");
            if (value == null) {
                continue;
            }
            String line = metric.get("key") + "," + metric.get("label") + "," + value + ",";
            assertTrue(csv.contains(line), "CSV 指标与 JSON 不一致：" + line);
        }
    }

    @Test
    void illegalReportTypeIsRejected() {
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                () -> reportService.build("UNKNOWN", QUERY));
        assertEquals("非法报表类型", unsupported.getMessage());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> reportService.build(null, QUERY));
        assertEquals("非法报表类型", missing.getMessage());
    }

    private static Map<String, Object> ticketOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total", 42L);
        overview.put("resolved", 30L);
        overview.put("closed", 20L);
        overview.put("processing", 5L);
        overview.put("cancelled", 2L);
        return overview;
    }

    private static Map<String, Object> point(String date, long created, long resolved, long closed) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("date", date);
        point.put("created", created);
        point.put("resolved", resolved);
        point.put("closed", closed);
        return point;
    }

    private static Map<String, Object> slaOverview() {
        Map<String, Object> firstResponse = new LinkedHashMap<>();
        firstResponse.put("numerator", 8L);
        firstResponse.put("denominator", 10L);
        firstResponse.put("rate", 80.0);

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("numerator", 6L);
        resolution.put("denominator", 8L);
        resolution.put("rate", 75.0);
        resolution.put("proxy", true);

        Map<String, Object> priority = new LinkedHashMap<>();
        priority.put("priority", "HIGH");
        priority.put("denominator", 10L);
        priority.put("numerator", 8L);
        priority.put("rate", 80.0);
        priority.put("avgFirstResponseMinutes", 30.0);
        priority.put("overdue", 1L);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("firstResponse", firstResponse);
        overview.put("resolution", resolution);
        overview.put("overallRate", 80.0);
        overview.put("overallNumerator", 8L);
        overview.put("overallDenominator", 10L);
        overview.put("warningCount", 2L);
        overview.put("overdueCount", 1L);
        overview.put("overdueCompleted", 3L);
        overview.put("missingSla", 4L);
        overview.put("avgFirstResponseMinutes", 12.3);
        overview.put("avgResolutionMinutes", 45.6);
        overview.put("priorityComparison", List.of(priority));
        overview.put("notes", List.of("取消工单不计入任何 SLA 分母"));
        return overview;
    }

    private static Map<String, Object> agentPerformance() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agentName", "张三");
        row.put("username", "zhangsan");
        row.put("active", true);
        row.put("assignedTotal", 10L);
        row.put("resolvedTotal", 4L);
        row.put("currentOccupied", 2L);
        row.put("completionRate", 40.0);
        row.put("slaRate", 80.0);
        row.put("avgFirstResponseMinutes", 30.0);
        row.put("avgResolutionMinutes", 120.0);
        row.put("overdueTotal", 1L);
        row.put("weightedResolved", 10L);
        row.put("loadPercent", 40.0);
        row.put("score", 74.0);
        row.put("lowSample", false);

        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("agents", List.of(row));
        performance.put("notes", List.of("取消工单不计入人员绩效"));
        return performance;
    }

    private static Map<String, Object> dispatchOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("recommendationCount", 12L);
        overview.put("decisionCount", 6L);
        overview.put("adoptionRate", 50.0);
        overview.put("rejectionRate", 50.0);
        overview.put("pendingCount", 2L);
        overview.put("avgConfirmSeconds", 120.0);
        overview.put("notes", List.of("待确认不计入采纳率分母"));
        return overview;
    }

    private static Map<String, Object> dispatchRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketNo", "SF20261110-0001");
        row.put("priority", "HIGH");
        row.put("recommendAt", "2026-11-10T10:00:00");
        row.put("agentName", "张三");
        row.put("accepted", true);
        row.put("confirmSeconds", 120L);
        return row;
    }

    private static Map<String, Object> aiOverview() {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("analysisType", "CLASSIFICATION");
        feature.put("total", 100L);
        feature.put("success", 80L);
        feature.put("failed", 20L);
        feature.put("successRate", 80.0);
        feature.put("avgLatencyMs", 123.4);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalCalls", 103L);
        overview.put("successCount", 80L);
        overview.put("failedCount", 20L);
        overview.put("pendingCount", 3L);
        overview.put("successRate", 80.0);
        overview.put("avgLatencyMs", 123.4);
        overview.put("features", List.of(feature));
        overview.put("notes", List.of("调用成功率与建议采纳率是两个独立指标"));
        return overview;
    }

    private static Map<String, Object> aiSuggestions() {
        Map<String, Object> suggestions = new LinkedHashMap<>();
        suggestions.put("total", 10L);
        suggestions.put("pending", 2L);
        suggestions.put("accepted", 6L);
        suggestions.put("rejected", 2L);
        suggestions.put("decided", 8L);
        suggestions.put("adoptionRate", 75.0);
        suggestions.put("notes", List.of("建议采纳率排除待确认"));
        return suggestions;
    }

    private static Object metricValue(List<Map<String, Object>> metrics, String key) {
        return metrics.stream()
                .filter(metric -> key.equals(metric.get("key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标：" + key))
                .get("value");
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
