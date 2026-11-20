package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AgentAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.AgentAnalyticsRepository.AgentStatRow;
import com.serviceflow.ai.analytics.repository.AgentAnalyticsRepository.DailyAgentStat;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人员绩效分析：按 phase6 spec 4.3 的口径组装指标与综合分。
 *
 * 关键口径：
 * - 人员集合是 users.role='AGENT'，含禁用账号；禁用只影响 active 展示，不剔除数据。
 * - 完成率与响应达标率分母为 0 时返回 null，不填 0 冒充有效结果。
 * - 当前占用与负载是实时快照，与日期区间无关。
 * - 区间分配工单少于 5 的人员标记样本不足，不参与有效排名。
 */
@Service
public class AgentAnalyticsService {

    /** 样本不足阈值：区间内分配工单少于该值不参与有效排名 */
    private static final long MIN_SAMPLE = 5;

    private final AgentAnalyticsRepository repository;
    private final AnalyticsSecurity security;

    public AgentAnalyticsService(AgentAnalyticsRepository repository, AnalyticsSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public Map<String, Object> performance(AnalyticsQuery query) {
        security.requireViewer();
        List<AgentStatRow> rows = repository.agentStats(query);

        long maxWeightedResolved = rows.stream().mapToLong(AgentStatRow::weightedResolved).max().orElse(0L);

        List<Map<String, Object>> valid = new ArrayList<>();
        List<Map<String, Object>> lowSample = new ArrayList<>();
        boolean missingMaxConcurrent = false;

        for (AgentStatRow row : rows) {
            Double completionRate = percent(row.resolvedTotal(), row.assignedTotal());
            Double slaRate = percent(row.slaNumerator(), row.slaDenominator());
            Double loadPercent = row.maxConcurrent() == null || row.maxConcurrent() <= 0
                    ? null : AnalyticsRepository.round1(row.currentOccupied() * 100.0 / row.maxConcurrent());
            if (loadPercent == null) {
                missingMaxConcurrent = true;
            }

            double slaScore = slaRate == null ? 0.0 : slaRate;
            double completionScore = completionRate == null ? 0.0 : completionRate;
            double volumeScore = maxWeightedResolved == 0
                    ? 0.0 : AnalyticsRepository.round1(row.weightedResolved() * 100.0 / maxWeightedResolved);
            double loadScore = loadPercent == null ? 0.0 : AnalyticsRepository.round1(Math.max(0.0, 100.0 - loadPercent));
            double score = row.assignedTotal() == 0 ? 0.0 : AnalyticsRepository.round1(
                    0.5 * slaScore + 0.2 * completionScore + 0.2 * volumeScore + 0.1 * loadScore);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", row.agentId());
            item.put("agentName", row.agentName());
            item.put("username", row.username());
            item.put("active", row.status() == 1);
            item.put("maxConcurrent", row.maxConcurrent());
            item.put("assignedTotal", row.assignedTotal());
            item.put("resolvedTotal", row.resolvedTotal());
            item.put("currentOccupied", row.currentOccupied());
            item.put("completionRate", completionRate);
            item.put("avgFirstResponseMinutes", roundOrNull(row.avgFirstResponseMinutes()));
            item.put("avgResolutionMinutes", roundOrNull(row.avgResolutionMinutes()));
            item.put("slaRate", slaRate);
            item.put("loadPercent", loadPercent);
            item.put("overdueTotal", row.overdueTotal());
            item.put("weightedResolved", row.weightedResolved());
            item.put("slaScore", AnalyticsRepository.round1(slaScore));
            item.put("completionScore", AnalyticsRepository.round1(completionScore));
            item.put("volumeScore", volumeScore);
            item.put("loadScore", loadScore);
            item.put("score", score);
            item.put("lowSample", row.assignedTotal() < MIN_SAMPLE);
            item.put("rank", null);

            if (row.assignedTotal() < MIN_SAMPLE) {
                lowSample.add(item);
            } else {
                valid.add(item);
            }
        }

        Comparator<Map<String, Object>> order = (a, b) -> {
            int byScore = Double.compare(number(b, "score"), number(a, "score"));
            if (byScore != 0) {
                return byScore;
            }
            return Long.compare(((Number) b.get("assignedTotal")).longValue(),
                    ((Number) a.get("assignedTotal")).longValue());
        };
        valid.sort(order);
        lowSample.sort(order);
        for (int i = 0; i < valid.size(); i++) {
            valid.get(i).put("rank", i + 1);
        }

        List<Map<String, Object>> agents = new ArrayList<>(valid);
        agents.addAll(lowSample);

        List<String> notes = new ArrayList<>();
        notes.add("综合分 = 0.5×SLA响应达标分 + 0.2×完成率分 + 0.2×优先级加权完成量分 + 0.1×负载健康分，四项均为 0-100");
        notes.add("优先级加权完成量按 URGENT=4 / HIGH=3 / MEDIUM=2 / LOW=1 求和，并在当前筛选结果内归一化");
        notes.add("当前处理中与负载为实时快照，排名会随当前负载变化");
        notes.add("区间内分配工单少于 5 的人员判定为样本不足，不参与有效排名");
        notes.add("区间内无分配工单的人员综合分记 0，避免「无工作量却因空闲而得分」");
        notes.add("取消工单不计入人员绩效");
        if (missingMaxConcurrent) {
            notes.add("未配置最大并发，负载分计 0");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", agents);
        result.put("maxWeightedResolved", maxWeightedResolved);
        result.put("notes", notes);
        return result;
    }

    public Map<String, Object> ranking(AnalyticsQuery query) {
        security.requireViewer();
        Map<String, Object> performance = performance(query);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", performance.get("agents"));
        result.put("notes", performance.get("notes"));
        return result;
    }

    public Map<String, Object> trend(AnalyticsQuery query, Long agentId) {
        security.requireViewer();
        Map<LocalDate, DailyAgentStat> rows = new LinkedHashMap<>();
        for (DailyAgentStat row : repository.agentTrend(query, agentId)) {
            rows.put(row.date(), row);
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date = query.startDate(); !date.isAfter(query.endDate()); date = date.plusDays(1)) {
            DailyAgentStat row = rows.get(date);
            long denominator = row == null ? 0L : row.slaDenominator();
            long numerator = row == null ? 0L : row.slaNumerator();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("resolved", row == null ? 0L : row.resolved());
            point.put("avgFirstResponseMinutes", row == null ? null : roundOrNull(row.avgFirstResponseMinutes()));
            point.put("responseSlaRate", percent(numerator, denominator));
            point.put("denominator", denominator);
            points.add(point);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        result.put("note", "按工单创建日期分组，响应达标率与 SLA 模块同口径（取消工单不计入分母），缺失日期补 0");
        return result;
    }

    private static double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
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
