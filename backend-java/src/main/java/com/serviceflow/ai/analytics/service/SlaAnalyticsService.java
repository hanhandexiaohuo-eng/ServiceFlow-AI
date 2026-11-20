package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.BucketCount;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.DailySlaCount;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.PrioritySlaStat;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.SlaAggregate;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository.StatusCount;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SLA 效率分析：严格按 phase6 spec 4.2 的口径组装指标。
 *
 * 关键口径：
 * - 取消工单不进入任何 SLA 分母（由仓库 SQL 保证）。
 * - 解决达标率是同一 sla_due_at 截止时间下的代理指标，项目没有独立的解决时限字段。
 * - 预警/超时只统计当前活动工单，不受日期区间影响。
 */
@Service
public class SlaAnalyticsService {

    private static final List<String> PRIORITY_ORDER =
            List.of("URGENT", "HIGH", "MEDIUM", "LOW");
    private static final List<String> STATE_ORDER =
            List.of("ON_TRACK", "WARNING", "OVERDUE", "COMPLETED");
    private static final List<String> BUCKET_ORDER =
            List.of("<60", "60-240", "240-480", "480-1440", "1440-4320", ">4320");
    /** 每日样本量低于该值视为样本不足，前端需降低结论权重 */
    private static final long MIN_DAILY_SAMPLE = 5;

    private final AnalyticsRepository repository;
    private final AnalyticsSecurity security;

    public SlaAnalyticsService(AnalyticsRepository repository, AnalyticsSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public Map<String, Object> overview(AnalyticsQuery query) {
        security.requireViewer();
        SlaAggregate aggregate = repository.slaAggregate(query);

        Map<String, Object> firstResponse = new LinkedHashMap<>();
        firstResponse.put("numerator", aggregate.nResp());
        firstResponse.put("denominator", aggregate.dResp());
        firstResponse.put("rate", percent(aggregate.nResp(), aggregate.dResp()));

        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("numerator", aggregate.nResolve());
        resolution.put("denominator", aggregate.dResolve());
        resolution.put("rate", percent(aggregate.nResolve(), aggregate.dResolve()));
        resolution.put("proxy", true);

        Map<String, Long> stateCounts = new LinkedHashMap<>();
        for (StatusCount row : repository.slaStatusDistribution(query)) {
            stateCounts.put(row.state(), row.count());
        }
        List<Map<String, Object>> statusDistribution = new ArrayList<>();
        for (String state : STATE_ORDER) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("state", state);
            item.put("count", stateCounts.getOrDefault(state, 0L));
            statusDistribution.add(item);
        }

        List<Map<String, Object>> firstResponseBuckets =
                fillBuckets(repository.firstResponseBuckets(query));
        List<Map<String, Object>> resolutionBuckets =
                fillBuckets(repository.resolutionBuckets(query));

        Map<String, PrioritySlaStat> priorityRows = new LinkedHashMap<>();
        for (PrioritySlaStat row : repository.slaPriorityComparison(query)) {
            priorityRows.put(row.priority(), row);
        }
        List<Map<String, Object>> priorityComparison = new ArrayList<>();
        for (String priority : PRIORITY_ORDER) {
            PrioritySlaStat row = priorityRows.get(priority);
            long denominator = row == null ? 0L : row.denominator();
            long numerator = row == null ? 0L : row.numerator();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("priority", priority);
            item.put("denominator", denominator);
            item.put("numerator", numerator);
            item.put("rate", percent(numerator, denominator));
            item.put("avgFirstResponseMinutes",
                    row == null || row.avgFirstResponseMinutes() == null
                            ? null : AnalyticsRepository.round1(row.avgFirstResponseMinutes()));
            item.put("overdue", row == null ? 0L : row.overdue());
            priorityComparison.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("firstResponse", firstResponse);
        result.put("resolution", resolution);
        result.put("overallRate", percent(aggregate.nResp(), aggregate.dResp()));
        result.put("overallNumerator", aggregate.nResp());
        result.put("overallDenominator", aggregate.dResp());
        result.put("warningCount", aggregate.warningCount());
        result.put("overdueCount", aggregate.overdueCount());
        result.put("overdueCompleted", aggregate.overdueCompleted());
        result.put("missingSla", aggregate.missingSla());
        result.put("avgFirstResponseMinutes", roundOrNull(aggregate.avgFirstResponseMinutes()));
        result.put("avgResolutionMinutes", roundOrNull(aggregate.avgResolutionMinutes()));
        result.put("statusDistribution", statusDistribution);
        result.put("firstResponseBuckets", firstResponseBuckets);
        result.put("resolutionBuckets", resolutionBuckets);
        result.put("priorityComparison", priorityComparison);
        result.put("notes", List.of(
                "取消工单不计入任何 SLA 分母",
                "解决达标率采用同一 sla_due_at 截止时间下的代理口径，项目没有独立的解决时限字段",
                "预警/超时只统计当前活动工单（待分配/已分配/已接单/处理中），不受日期区间影响",
                "sla_due_at 为空的历史工单单独计数，不进入任何分母"));
        return result;
    }

    public Map<String, Object> trend(AnalyticsQuery query) {
        security.requireViewer();
        Map<LocalDate, DailySlaCount> rows = new LinkedHashMap<>();
        for (DailySlaCount row : repository.slaDailyTrend(query)) {
            rows.put(row.date(), row);
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date = query.startDate(); !date.isAfter(query.endDate()); date = date.plusDays(1)) {
            DailySlaCount row = rows.get(date);
            long denominator = row == null ? 0L : row.denominator();
            long numerator = row == null ? 0L : row.numerator();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("denominator", denominator);
            point.put("numerator", numerator);
            point.put("rate", percent(numerator, denominator));
            point.put("sampleSufficient", denominator >= MIN_DAILY_SAMPLE);
            points.add(point);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        return result;
    }

    private static List<Map<String, Object>> fillBuckets(List<BucketCount> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (BucketCount row : rows) {
            counts.put(row.bucket(), row.count());
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (String bucket : BUCKET_ORDER) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucket", bucket);
            item.put("count", counts.getOrDefault(bucket, 0L));
            buckets.add(item);
        }
        return buckets;
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
