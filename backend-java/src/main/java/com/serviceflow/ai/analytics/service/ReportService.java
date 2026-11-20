package com.serviceflow.ai.analytics.service;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.repository.AnalyticsRepository;
import com.serviceflow.ai.auth.entity.User;
import com.serviceflow.ai.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 综合报表：五类报表全部复用既有分析 service 的计算结果，不重复写 SQL。
 *
 * JSON 与 CSV 导出共用同一个 build 方法，保证同一筛选条件下数字一致；
 * 两者只有明细行上限不同（页面 2000 行、导出 10000 行），截断会写入 notes。
 */
@Service
public class ReportService {

    /** 页面 JSON 明细上限，避免一次返回过多行 */
    private static final int JSON_DETAIL_LIMIT = 2000;
    /** CSV 导出明细上限 */
    public static final int EXPORT_DETAIL_LIMIT = 10000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final TicketAnalyticsService ticketAnalyticsService;
    private final SlaAnalyticsService slaAnalyticsService;
    private final AgentAnalyticsService agentAnalyticsService;
    private final DispatchAnalyticsService dispatchAnalyticsService;
    private final AiAnalyticsService aiAnalyticsService;
    private final UserRepository userRepository;
    private final AnalyticsSecurity security;

    public ReportService(TicketAnalyticsService ticketAnalyticsService,
                         SlaAnalyticsService slaAnalyticsService,
                         AgentAnalyticsService agentAnalyticsService,
                         DispatchAnalyticsService dispatchAnalyticsService,
                         AiAnalyticsService aiAnalyticsService,
                         UserRepository userRepository,
                         AnalyticsSecurity security) {
        this.ticketAnalyticsService = ticketAnalyticsService;
        this.slaAnalyticsService = slaAnalyticsService;
        this.agentAnalyticsService = agentAnalyticsService;
        this.dispatchAnalyticsService = dispatchAnalyticsService;
        this.aiAnalyticsService = aiAnalyticsService;
        this.userRepository = userRepository;
        this.security = security;
    }

    public Map<String, Object> build(String type, AnalyticsQuery query) {
        security.requireViewer();
        return buildReport(parseType(type), query, JSON_DETAIL_LIMIT);
    }

    public Map<String, Object> build(String type, AnalyticsQuery query, int detailLimit) {
        security.requireViewer();
        return buildReport(parseType(type), query, detailLimit);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildReport(ReportType reportType, AnalyticsQuery query, int detailLimit) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        switch (reportType) {
            case TICKET_OPERATIONS -> {
                Map<String, Object> overview = ticketAnalyticsService.overview(query);
                Map<String, Object> sla = slaAnalyticsService.overview(query);
                metrics.add(metric("total", "工单总数", overview.get("total"), "区间内创建的工单数"));
                metrics.add(metric("resolved", "已解决", overview.get("resolved"),
                        "区间内 status='RESOLVED' 且 resolved_at 落在区间"));
                metrics.add(metric("closed", "已关闭", overview.get("closed"),
                        "区间内 status='CLOSED' 且 closed_at 落在区间"));
                metrics.add(metric("processing", "处理中（当前）", overview.get("processing"),
                        "实时 status IN ('ASSIGNED','ACCEPTED','PROCESSING')，不受区间影响"));
                metrics.add(metric("cancelled", "已取消", overview.get("cancelled"),
                        "区间内 status='CANCELLED' 且 cancelled_at 落在区间"));
                metrics.add(metric("avgResolutionMinutes", "平均解决时长（分钟）",
                        sla.get("avgResolutionMinutes"), "完成工单 AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))"));

                columns.add(column("date", "日期"));
                columns.add(column("created", "新增"));
                columns.add(column("resolved", "解决"));
                columns.add(column("closed", "关闭"));
                rows = (List<Map<String, Object>>) ticketAnalyticsService.trend(query).get("points");

                notes.add("取消工单计入总量与状态分布，但不计入任何 SLA 与绩效分母");
                notes.add("处理中为实时值，不受日期区间影响");
            }
            case SLA_EFFICIENCY -> {
                Map<String, Object> sla = slaAnalyticsService.overview(query);
                Map<String, Object> firstResponse = (Map<String, Object>) sla.get("firstResponse");
                Map<String, Object> resolution = (Map<String, Object>) sla.get("resolution");

                metrics.add(metric("overallRate", "响应达标率", sla.get("overallRate"),
                        "N_resp / D_resp × 100，取消工单不进分母"));
                metrics.add(metric("overallNumerator", "响应达标分子", sla.get("overallNumerator"), "first_response_at <= sla_due_at"));
                metrics.add(metric("overallDenominator", "响应达标分母", sla.get("overallDenominator"),
                        "有 sla_due_at 且（已响应 或 已过截止时间）"));
                metrics.add(metric("resolutionRate", "解决达标率（代理）", resolution.get("rate"),
                        "完成时间 <= sla_due_at，同一截止时间下的代理指标"));
                metrics.add(metric("resolutionNumerator", "解决达标分子", resolution.get("numerator"), "完成时间 <= sla_due_at"));
                metrics.add(metric("resolutionDenominator", "解决达标分母", resolution.get("denominator"),
                        "有 sla_due_at 且已完成的工单"));
                metrics.add(metric("warningCount", "预警数量", sla.get("warningCount"), "当前活动工单剩余 0-120 分钟"));
                metrics.add(metric("overdueCount", "当前超时数量", sla.get("overdueCount"), "当前活动工单 NOW() > sla_due_at"));
                metrics.add(metric("overdueCompleted", "历史超时完成", sla.get("overdueCompleted"), "完成时间 > sla_due_at"));
                metrics.add(metric("avgFirstResponseMinutes", "平均首次响应（分钟）",
                        sla.get("avgFirstResponseMinutes"), "AVG(TIMESTAMPDIFF(MINUTE, created_at, first_response_at))"));
                metrics.add(metric("avgResolutionMinutes", "平均解决时长（分钟）",
                        sla.get("avgResolutionMinutes"), "AVG(TIMESTAMPDIFF(MINUTE, created_at, 完成时间))"));
                metrics.add(metric("missingSla", "SLA 记录缺失", sla.get("missingSla"),
                        "非取消且 sla_due_at 为空，不进入任何分母"));

                columns.add(column("priority", "优先级"));
                columns.add(column("denominator", "响应分母"));
                columns.add(column("numerator", "响应分子"));
                columns.add(column("rate", "响应达标率"));
                columns.add(column("avgFirstResponseMinutes", "平均首响（分钟）"));
                columns.add(column("overdue", "当前超时"));
                rows = (List<Map<String, Object>>) sla.get("priorityComparison");

                notes.addAll((List<String>) sla.get("notes"));
            }
            case AGENT_PERFORMANCE -> {
                Map<String, Object> performance = agentAnalyticsService.performance(query);
                List<Map<String, Object>> agents = (List<Map<String, Object>>) performance.get("agents");

                metrics.add(metric("agentCount", "人员数", (long) agents.size(), "users.role='AGENT' 的人员数（含禁用账号）"));
                metrics.add(metric("totalResolved", "总完成量",
                        agents.stream().mapToLong(row -> number(row, "resolvedTotal")).sum(),
                        "全部人员区间内 RESOLVED/CLOSED 工单数之和"));
                metrics.add(metric("avgCompletionRate", "平均完成率", average(agents, "completionRate"),
                        "各人员完成率的算术平均，忽略分母为 0 的人员"));
                metrics.add(metric("avgScore", "平均综合分", average(agents, "score"),
                        "各人员综合绩效分的算术平均"));

                columns.add(column("agentName", "人员"));
                columns.add(column("username", "登录名"));
                columns.add(column("active", "账号状态"));
                columns.add(column("assignedTotal", "累计处理"));
                columns.add(column("resolvedTotal", "已解决"));
                columns.add(column("currentOccupied", "当前处理中"));
                columns.add(column("completionRate", "完成率"));
                columns.add(column("slaRate", "响应达标率"));
                columns.add(column("avgFirstResponseMinutes", "平均首响（分钟）"));
                columns.add(column("avgResolutionMinutes", "平均解决（分钟）"));
                columns.add(column("overdueTotal", "超时工单数"));
                columns.add(column("weightedResolved", "优先级加权完成量"));
                columns.add(column("loadPercent", "当前负载率"));
                columns.add(column("score", "综合分"));
                columns.add(column("lowSample", "样本不足"));

                for (Map<String, Object> agent : agents) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("agentName", agent.get("agentName"));
                    row.put("username", agent.get("username"));
                    row.put("active", Boolean.TRUE.equals(agent.get("active")) ? "正常" : "禁用");
                    row.put("assignedTotal", agent.get("assignedTotal"));
                    row.put("resolvedTotal", agent.get("resolvedTotal"));
                    row.put("currentOccupied", agent.get("currentOccupied"));
                    row.put("completionRate", agent.get("completionRate"));
                    row.put("slaRate", agent.get("slaRate"));
                    row.put("avgFirstResponseMinutes", agent.get("avgFirstResponseMinutes"));
                    row.put("avgResolutionMinutes", agent.get("avgResolutionMinutes"));
                    row.put("overdueTotal", agent.get("overdueTotal"));
                    row.put("weightedResolved", agent.get("weightedResolved"));
                    row.put("loadPercent", agent.get("loadPercent"));
                    row.put("score", agent.get("score"));
                    row.put("lowSample", Boolean.TRUE.equals(agent.get("lowSample")) ? "是" : "否");
                    rows.add(row);
                }

                notes.addAll((List<String>) performance.get("notes"));
                notes.add("负载为实时快照，排名会随当前负载变化");
            }
            case DISPATCH_EFFECT -> {
                Map<String, Object> dispatch = dispatchAnalyticsService.overview(query);
                metrics.add(metric("recommendationCount", "推荐次数", dispatch.get("recommendationCount"),
                        "区间内 action='RECOMMEND' 日志行数"));
                metrics.add(metric("decisionCount", "已决策工单数", dispatch.get("decisionCount"),
                        "推荐后出现确认分配的工单数（采纳 + 拒绝）"));
                metrics.add(metric("adoptionRate", "推荐采纳率", dispatch.get("adoptionRate"),
                        "采纳工单数 / 已决策工单数 × 100"));
                metrics.add(metric("rejectionRate", "推荐拒绝率", dispatch.get("rejectionRate"),
                        "拒绝工单数 / 已决策工单数 × 100"));
                metrics.add(metric("pendingCount", "待确认", dispatch.get("pendingCount"),
                        "有推荐但无后续分配的工单数，不计入采纳率分母"));
                metrics.add(metric("avgConfirmSeconds", "平均确认间隔（秒）", dispatch.get("avgConfirmSeconds"),
                        "AVG(TIMESTAMPDIFF(SECOND, 推荐时间, 确认分配时间))，非算法耗时"));

                columns.add(column("ticketNo", "工单编号"));
                columns.add(column("priority", "优先级"));
                columns.add(column("recommendAt", "推荐时间"));
                columns.add(column("agentName", "分配人员"));
                columns.add(column("accepted", "是否采纳"));
                columns.add(column("confirmSeconds", "确认间隔（秒）"));
                rows = new ArrayList<>(dispatchAnalyticsService.ticketRows(query));

                notes.addAll((List<String>) dispatch.get("notes"));
            }
            case AI_EFFECT -> {
                Map<String, Object> ai = aiAnalyticsService.overview(query);
                Map<String, Object> suggestions = aiAnalyticsService.suggestions(query);

                metrics.add(metric("totalCalls", "调用总数", ai.get("totalCalls"), "区间内 ai_analyses 记录数"));
                metrics.add(metric("successCount", "成功调用", ai.get("successCount"), "status='SUCCESS'"));
                metrics.add(metric("failedCount", "失败调用", ai.get("failedCount"), "status='FAILED'"));
                metrics.add(metric("pendingCount", "待处理调用", ai.get("pendingCount"), "status='PENDING'，不进成功率分母"));
                metrics.add(metric("successRate", "调用成功率", ai.get("successRate"),
                        "SUCCESS / (SUCCESS + FAILED) × 100"));
                metrics.add(metric("avgLatencyMs", "平均耗时（毫秒）", ai.get("avgLatencyMs"),
                        "仅 SUCCESS/FAILED 且 latency_ms 非空"));
                metrics.add(metric("suggestionTotal", "建议总数", suggestions.get("total"), "区间内 ai_suggestions 记录数"));
                metrics.add(metric("suggestionAccepted", "已采纳建议", suggestions.get("accepted"),
                        "status IN ('ACCEPTED','EXECUTED')"));
                metrics.add(metric("suggestionRejected", "已拒绝建议", suggestions.get("rejected"), "status='REJECTED'"));
                metrics.add(metric("suggestionAdoptionRate", "建议采纳率", suggestions.get("adoptionRate"),
                        "已采纳 / 已决策 × 100，待确认不进分母"));

                columns.add(column("analysisType", "分析类型"));
                columns.add(column("total", "总数"));
                columns.add(column("success", "成功"));
                columns.add(column("failed", "失败"));
                columns.add(column("successRate", "成功率"));
                columns.add(column("avgLatencyMs", "平均耗时（毫秒）"));
                rows = (List<Map<String, Object>>) ai.get("features");

                notes.addAll((List<String>) ai.get("notes"));
                notes.addAll((List<String>) suggestions.get("notes"));
                notes.add("AI 效果报表不计算模型准确率或建议正确率，项目没有真值数据");
            }
        }

        if (rows.size() > detailLimit) {
            notes.add("明细共 " + rows.size() + " 行，已截断为前 " + detailLimit + " 行");
            rows = new ArrayList<>(rows.subList(0, detailLimit));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportName", reportType.label);
        result.put("type", reportType.name());
        result.put("generatedAt", LocalDateTime.now().format(TIMESTAMP));
        result.put("range", range(query));
        result.put("filters", filters(query));
        result.put("metrics", metrics);
        result.put("detailColumns", columns);
        result.put("detailRows", rows);
        result.put("notes", notes);
        return result;
    }

    private Map<String, Object> range(AnalyticsQuery query) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("startDate", query.startDate().toString());
        range.put("endDate", query.endDate().toString());
        range.put("label", query.startDate() + " ~ " + query.endDate());
        return range;
    }

    private Map<String, Object> filters(AnalyticsQuery query) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("ticketType", query.ticketType());
        filters.put("priority", query.priority());
        filters.put("assigneeId", query.assigneeId());
        String assigneeName = null;
        if (query.assigneeId() != null) {
            assigneeName = userRepository.findById(query.assigneeId())
                    .map(User::getName)
                    .orElse(null);
        }
        filters.put("assigneeName", assigneeName);
        return filters;
    }

    private static Map<String, Object> metric(String key, String label, Object value, String formula) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("key", key);
        metric.put("label", label);
        metric.put("value", value);
        metric.put("formula", formula);
        return metric;
    }

    private static Map<String, Object> column(String key, String label) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", key);
        column.put("label", label);
        return column;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Double average(List<Map<String, Object>> rows, String key) {
        double sum = 0;
        long samples = 0;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value == null) {
                continue;
            }
            sum += ((Number) value).doubleValue();
            samples++;
        }
        return samples == 0 ? null : AnalyticsRepository.round1(sum / samples);
    }

    private static ReportType parseType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("非法报表类型");
        }
        try {
            return ReportType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法报表类型");
        }
    }

    public enum ReportType {
        TICKET_OPERATIONS("工单运营汇总"),
        SLA_EFFICIENCY("SLA 效率汇总"),
        AGENT_PERFORMANCE("人员绩效明细"),
        DISPATCH_EFFECT("智能调度效果"),
        AI_EFFECT("AI 效果汇总");

        private final String label;

        ReportType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
