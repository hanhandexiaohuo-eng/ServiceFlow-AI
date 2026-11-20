package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.service.AiAnalyticsService;
import com.serviceflow.ai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** AI 效果接口：调用成功率与建议采纳率分别由两个端点提供，避免指标混淆。 */
@Tag(name = "15 AI 效果分析", description = "AI 采纳率与效果")
@RestController
@RequestMapping("/api/analytics")
public class AiAnalyticsController {

    private final AiAnalyticsService aiAnalyticsService;

    public AiAnalyticsController(AiAnalyticsService aiAnalyticsService) {
        this.aiAnalyticsService = aiAnalyticsService;
    }

    /** AI 调用统计与功能/错误分布 */
    @GetMapping("/ai/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(aiAnalyticsService.overview(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** AI 建议采纳统计 */
    @GetMapping("/ai/suggestions")
    public ApiResponse<Map<String, Object>> suggestions(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(aiAnalyticsService.suggestions(
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
