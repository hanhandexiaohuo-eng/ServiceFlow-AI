package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.service.AgentAnalyticsService;
import com.serviceflow.ai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 人员绩效接口：只做参数绑定与响应组装，计算口径在 AgentAnalyticsService。
 * 权限校验由 service 首行的 AnalyticsSecurity.requireViewer() 完成。
 */
@Tag(name = "13 人员绩效分析", description = "人员绩效统计")
@RestController
@RequestMapping("/api/analytics")
public class AgentAnalyticsController {

    private final AgentAnalyticsService agentAnalyticsService;

    public AgentAnalyticsController(AgentAnalyticsService agentAnalyticsService) {
        this.agentAnalyticsService = agentAnalyticsService;
    }

    /** 人员绩效明细 + 综合分 + 排名 */
    @GetMapping("/agents/performance")
    public ApiResponse<Map<String, Object>> performance(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(agentAnalyticsService.performance(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** 仅排名，复用 performance 的计算结果 */
    @GetMapping("/agents/ranking")
    public ApiResponse<Map<String, Object>> ranking(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(agentAnalyticsService.ranking(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** 绩效趋势：agentId 为人员选择，不传则全团队汇总 */
    @GetMapping("/agents/trend")
    public ApiResponse<Map<String, Object>> trend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Long agentId) {
        return ApiResponse.ok(agentAnalyticsService.trend(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId), agentId));
    }

    private static AnalyticsQuery buildQuery(String startDate, String endDate, String ticketType,
                                             String priority, Long assigneeId) {
        return new AnalyticsQuery(parseDate(startDate), parseDate(endDate),
                ticketType, priority, assigneeId);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
    }
}
