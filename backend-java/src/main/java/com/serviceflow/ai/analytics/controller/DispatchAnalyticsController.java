package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.service.DispatchAnalyticsService;
import com.serviceflow.ai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 智能调度效果接口：推荐采纳、技能匹配与优先级效果。 */
@Tag(name = "14 调度分析", description = "调度效率统计")
@RestController
@RequestMapping("/api/analytics")
public class DispatchAnalyticsController {

    private final DispatchAnalyticsService dispatchAnalyticsService;

    public DispatchAnalyticsController(DispatchAnalyticsService dispatchAnalyticsService) {
        this.dispatchAnalyticsService = dispatchAnalyticsService;
    }

    /** 调度全部指标 */
    @GetMapping("/dispatch/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(dispatchAnalyticsService.overview(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
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
