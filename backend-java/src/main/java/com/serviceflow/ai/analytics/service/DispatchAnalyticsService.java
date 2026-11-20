package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.analytics.repository.DispatchAnalyticsRepository;
import com.serviceflow.ai.analytics.repository.DispatchAnalyticsRepository.DispatchDecisionRow;
import com.serviceflow.ai.analytics.repository.DispatchAnalyticsRepository.SkillCheck;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智能调度效果分析：按 phase6 spec 4.4 的口径组装。
 *
 * 关键口径：
 * - 推荐采纳/拒绝按工单去重，待确认（推荐后没有 ASSIGN）不计入采纳率分母。
 * - 平均确认间隔是「推荐 → 确认分配」的时间差，不是调度算法耗时。
 * - 系统没有调度前负载快照，负载变化明确返回不可用。
 * - 历史 RECOMMEND 记录未保存推荐人（agent_id 为空），绝不虚构人员。
 */
@Service
public class DispatchAnalyticsService {

    private static final List<String> PRIORITY_ORDER = List.of("URGENT", "HIGH", "MEDIUM", "LOW");
    private static final List<String> SKILL_STATES = List.of("MATCHED", "PARTIAL", "UNMATCHED", "UNKNOWN");
    private static final String UNKNOWN_AGENT_NAME = "未记录推荐人";
    private static final String LOAD_CHANGE_NOTE = "系统未保存调度前负载快照，无法计算调度前后负载变化";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DispatchAnalyticsRepository repository;
    private final AnalyticsSecurity security;

    public DispatchAnalyticsService(DispatchAnalyticsRepository repository, AnalyticsSecurity security) {
        this.repository = repository;
        this.security = security;
    }

    public Map<String, Object> overview(AnalyticsQuery query) {
        security.requireViewer();
        long recommendationCount = repository.recommendationCount(query);
        List<TicketDecision> decisions = decisions(query);
        List<SkillCheck> skillChecks = repository.skillChecks(query);

        long acceptedCount = decisions.stream().filter(TicketDecision::accepted).count();
        long rejectedCount = decisions.stream()
                .filter(d -> d.verifiable() && d.decided() && !d.accepted()).count();
        long unverifiableCount = decisions.stream()
                .filter(d -> !d.verifiable() && d.decided()).count();
        long pendingCount = decisions.stream().filter(d -> !d.decided()).count();
        long decisionCount = acceptedCount + rejectedCount;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommendationCount", recommendationCount);
        result.put("assignedAfterRecommend", decisionCount + unverifiableCount);
        result.put("acceptedCount", acceptedCount);
        result.put("rejectedCount", rejectedCount);
        result.put("unverifiableCount", unverifiableCount);
        result.put("pendingCount", pendingCount);
        result.put("decisionCount", decisionCount);
        result.put("adoptionRate", percent(acceptedCount, decisionCount));
        result.put("rejectionRate", percent(rejectedCount, decisionCount));
        result.put("avgConfirmSeconds", averageConfirmSeconds(decisions));
        result.put("lowSample", recommendationCount < 10);
        result.put("loadChangeAvailable", false);
        result.put("loadChangeNote", LOAD_CHANGE_NOTE);
        result.put("agents", agentDistribution(decisions));
        result.put("priorityEffects", priorityEffects(decisions));
        result.put("skillMatch", skillMatch(skillChecks));
        result.put("adoptionTrend", adoptionTrend(query, decisions));
        result.put("notes", List.of(
                "采纳率分母只含已决策推荐（推荐后出现确认分配的工单），待确认不计入分母",
                "采纳/拒绝/待确认按工单去重；推荐次数按 RECOMMEND 日志行数统计",
                "平均确认间隔是推荐到确认分配的时间差，不是调度算法耗时",
                LOAD_CHANGE_NOTE,
                "技能匹配基于当前技能配置，非调度时快照",
                "历史 RECOMMEND 记录未保存推荐人，人员推荐分布不可用",
                "推荐人未记录时无法比对确认分配人员，此类工单单列为 unverifiableCount，不计入采纳率分母（不臆断为拒绝）"));
        return result;
    }

    /** 报表明细行：一张工单一行，与 overview 使用同一批调度日志。 */
    public List<Map<String, Object>> ticketRows(AnalyticsQuery query) {
        security.requireViewer();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketDecision decision : decisions(query)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticketNo", decision.ticketNo());
            row.put("priority", decision.priority());
            row.put("recommendAt", decision.recommendAt() == null
                    ? null : decision.recommendAt().format(TIMESTAMP));
            row.put("agentName", decision.assignAgentName());
            row.put("accepted", decision.decided() ? decision.accepted() : null);
            row.put("confirmSeconds", decision.confirmSeconds());
            rows.add(row);
        }
        return rows;
    }

    private List<TicketDecision> decisions(AnalyticsQuery query) {
        Map<Long, List<DispatchDecisionRow>> grouped = new LinkedHashMap<>();
        for (DispatchDecisionRow row : repository.decisionRows(query)) {
            grouped.computeIfAbsent(row.ticketId(), key -> new ArrayList<>()).add(row);
        }

        List<TicketDecision> decisions = new ArrayList<>();
        for (List<DispatchDecisionRow> rows : grouped.values()) {
            DispatchDecisionRow recommend = rows.get(0);
            DispatchDecisionRow firstAssign = rows.stream()
                    .filter(row -> row.assignAgentId() != null && row.assignAt() != null)
                    .findFirst().orElse(null);
            boolean accepted = recommend.recommendAgentId() != null && rows.stream()
                    .anyMatch(row -> Objects.equals(row.assignAgentId(), recommend.recommendAgentId()));
            DispatchDecisionRow chosenAssign = accepted
                    ? rows.stream()
                            .filter(row -> Objects.equals(row.assignAgentId(), recommend.recommendAgentId()))
                            .findFirst().orElse(firstAssign)
                    : firstAssign;
            decisions.add(new TicketDecision(
                    recommend.ticketId(),
                    recommend.ticketNo(),
                    recommend.priority(),
                    recommend.recommendAt(),
                    recommend.recommendAgentId(),
                    recommend.recommendAgentName(),
                    chosenAssign == null ? null : chosenAssign.assignAgentId(),
                    chosenAssign == null ? null : chosenAssign.assignAgentName(),
                    chosenAssign == null ? null : chosenAssign.assignAt(),
                    accepted,
                    recommend.recommendAgentId() != null));
        }
        return decisions;
    }

    private static List<Map<String, Object>> agentDistribution(List<TicketDecision> decisions) {
        Map<Long, AgentAggregate> aggregates = new LinkedHashMap<>();
        for (TicketDecision decision : decisions) {
            AgentAggregate aggregate = aggregates.computeIfAbsent(decision.recommendAgentId(),
                    key -> new AgentAggregate(key, key == null ? UNKNOWN_AGENT_NAME : decision.recommendAgentName()));
            aggregate.recommendCount++;
            if (decision.verifiable() && decision.decided()) {
                if (decision.accepted()) {
                    aggregate.acceptedCount++;
                } else {
                    aggregate.rejectedCount++;
                }
            }
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentAggregate aggregate : aggregates.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", aggregate.agentId);
            item.put("agentName", aggregate.agentName == null ? UNKNOWN_AGENT_NAME : aggregate.agentName);
            item.put("recommendCount", aggregate.recommendCount);
            item.put("acceptedCount", aggregate.acceptedCount);
            item.put("rejectedCount", aggregate.rejectedCount);
            agents.add(item);
        }
        return agents;
    }

    private static List<Map<String, Object>> priorityEffects(List<TicketDecision> decisions) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (String priority : PRIORITY_ORDER) {
            counts.put(priority, new long[2]);
        }
        for (TicketDecision decision : decisions) {
            if (!decision.decided() || !decision.verifiable()) {
                continue;
            }
            long[] bucket = counts.get(decision.priority());
            if (bucket == null) {
                continue;
            }
            bucket[0]++;
            if (decision.accepted()) {
                bucket[1]++;
            }
        }
        List<Map<String, Object>> effects = new ArrayList<>();
        for (String priority : PRIORITY_ORDER) {
            long[] bucket = counts.get(priority);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("priority", priority);
            item.put("decisionCount", bucket[0]);
            item.put("acceptedCount", bucket[1]);
            item.put("adoptionRate", percent(bucket[1], bucket[0]));
            effects.add(item);
        }
        return effects;
    }

    private static List<Map<String, Object>> skillMatch(List<SkillCheck> checks) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : SKILL_STATES) {
            counts.put(state, 0L);
        }
        for (SkillCheck check : checks) {
            String state;
            if (check.requiredCount() == 0) {
                state = "UNKNOWN";
            } else if (check.matchedCount() >= check.requiredCount()) {
                state = "MATCHED";
            } else if (check.matchedCount() == 0) {
                state = "UNMATCHED";
            } else {
                state = "PARTIAL";
            }
            counts.put(state, counts.get(state) + 1);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String state : SKILL_STATES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("state", state);
            item.put("count", counts.get(state));
            result.add(item);
        }
        return result;
    }

    private static List<Map<String, Object>> adoptionTrend(AnalyticsQuery query, List<TicketDecision> decisions) {
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        for (TicketDecision decision : decisions) {
            if (!decision.decided() || !decision.verifiable() || decision.recommendAt() == null) {
                continue;
            }
            long[] bucket = counts.computeIfAbsent(decision.recommendAt().toLocalDate(), key -> new long[2]);
            bucket[0]++;
            if (decision.accepted()) {
                bucket[1]++;
            }
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (LocalDate date = query.startDate(); !date.isAfter(query.endDate()); date = date.plusDays(1)) {
            long[] bucket = counts.getOrDefault(date, new long[2]);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("decisionCount", bucket[0]);
            point.put("acceptedCount", bucket[1]);
            point.put("rate", percent(bucket[1], bucket[0]));
            points.add(point);
        }
        return points;
    }

    private static Double averageConfirmSeconds(List<TicketDecision> decisions) {
        long total = 0;
        long samples = 0;
        for (TicketDecision decision : decisions) {
            Long seconds = decision.confirmSeconds();
            if (seconds == null) {
                continue;
            }
            total += seconds;
            samples++;
        }
        return samples == 0 ? null : AnalyticsRepository.round1((double) total / samples);
    }

    private static Double percent(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return AnalyticsRepository.round1(numerator * 100.0 / denominator);
    }

    private static final class AgentAggregate {
        private final Long agentId;
        private final String agentName;
        private long recommendCount;
        private long acceptedCount;
        private long rejectedCount;

        private AgentAggregate(Long agentId, String agentName) {
            this.agentId = agentId;
            this.agentName = agentName;
        }
    }

    private record TicketDecision(long ticketId, String ticketNo, String priority,
                                  LocalDateTime recommendAt, Long recommendAgentId,
                                  String recommendAgentName, Long assignAgentId,
                                  String assignAgentName, LocalDateTime assignAt, boolean accepted,
                                  boolean verifiable) {

        private boolean decided() {
            return assignAt != null;
        }

        private Long confirmSeconds() {
            if (assignAt == null || recommendAt == null) {
                return null;
            }
            return Math.max(0L, Duration.between(recommendAt, assignAt).getSeconds());
        }
    }
}
