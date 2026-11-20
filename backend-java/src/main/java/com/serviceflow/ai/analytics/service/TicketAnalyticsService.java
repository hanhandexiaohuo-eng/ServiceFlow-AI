package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.DailyCount;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.GroupCount;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.PriorityStat;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.TrendCounts;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单运营分析：总量、每日趋势、类型/状态/优先级分布。
 * 数值全部来自数据库聚合；空区间返回 0 或空数组，日期缺失补 0 属于真实计数而非伪造数据。
 */
@Service
public class TicketAnalyticsService {

    private static final List<String> PRIORITY_ORDER =
            List.of("URGENT", "HIGH", "MEDIUM", "LOW");

    private final AnalyticsRepository repository;
    private final AnalyticsSecurity security;

    public TicketAnalyticsService(AnalyticsRepository repository, AnalyticsSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public Map<String, Object> overview(AnalyticsQuery query) {
        security.requireViewer();
        Map<String, Long> counts = repository.overviewCounts(query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", counts.getOrDefault("total", 0L));
        result.put("resolved", counts.getOrDefault("resolved", 0L));
        result.put("closed", counts.getOrDefault("closed", 0L));
        result.put("cancelled", counts.getOrDefault("cancelled", 0L));
        result.put("processing", counts.getOrDefault("processing", 0L));
        return result;
    }

    public Map<String, Object> trend(AnalyticsQuery query) {
        security.requireViewer();
        TrendCounts counts = repository.dailyTrend(query);
        Map<LocalDate, Long> created = toDateMap(counts.created());
        Map<LocalDate, Long> resolved = toDateMap(counts.resolved());
        Map<LocalDate, Long> closed = toDateMap(counts.closed());

        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date = query.startDate(); !date.isAfter(query.endDate()); date = date.plusDays(1)) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("created", created.getOrDefault(date, 0L));
            point.put("resolved", resolved.getOrDefault(date, 0L));
            point.put("closed", closed.getOrDefault(date, 0L));
            points.add(point);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        return result;
    }

    public Map<String, Object> distribution(AnalyticsQuery query) {
        security.requireViewer();

        List<GroupCount> typeRows = repository.distributionByType(query);
        long typeTotal = typeRows.stream().mapToLong(GroupCount::count).sum();
        List<Map<String, Object>> byType = new ArrayList<>();
        for (GroupCount row : typeRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", row.key());
            item.put("count", row.count());
            item.put("percent", percent(row.count(), typeTotal));
            byType.add(item);
        }

        List<GroupCount> statusRows = repository.distributionByStatus(query);
        long statusTotal = statusRows.stream().mapToLong(GroupCount::count).sum();
        List<Map<String, Object>> byStatus = new ArrayList<>();
        for (GroupCount row : statusRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", row.key());
            item.put("count", row.count());
            item.put("percent", percent(row.count(), statusTotal));
            byStatus.add(item);
        }

        Map<String, PriorityStat> priorityRows = new LinkedHashMap<>();
        for (PriorityStat row : repository.priorityStats(query)) {
            priorityRows.put(row.priority(), row);
        }
        List<Map<String, Object>> byPriority = new ArrayList<>();
        for (String priority : PRIORITY_ORDER) {
            PriorityStat row = priorityRows.get(priority);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("priority", priority);
            item.put("total", row == null ? 0L : row.total());
            item.put("resolved", row == null ? 0L : row.resolved());
            item.put("overdue", row == null ? 0L : row.overdue());
            item.put("avgHandleMinutes",
                    row == null || row.avgHandleMinutes() == null
                            ? null : AnalyticsRepository.round1(row.avgHandleMinutes()));
            byPriority.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byType", byType);
        result.put("byStatus", byStatus);
        result.put("byPriority", byPriority);
        return result;
    }

    private static Map<LocalDate, Long> toDateMap(List<DailyCount> rows) {
        Map<LocalDate, Long> map = new LinkedHashMap<>();
        for (DailyCount row : rows) {
            map.put(row.date(), row.count());
        }
        return map;
    }

    private static Double percent(long part, long total) {
        if (total == 0) {
            return null;
        }
        return AnalyticsRepository.round1(part * 100.0 / total);
    }
}
