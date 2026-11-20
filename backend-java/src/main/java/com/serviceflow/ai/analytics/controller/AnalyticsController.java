package com.serviceflow.ai.analytics.controller;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import com.serviceflow.ai.analytics.service.SlaAnalyticsService;
import com.serviceflow.ai.analytics.service.TicketAnalyticsService;
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
 * 运营分析接口：只做参数绑定与响应组装，统计口径与 SQL 全部在 service / repository。
 * 权限校验由各 service 首行的 AnalyticsSecurity.requireViewer() 完成。
 */
@Tag(name = "12 运营分析", description = "驾驶舱与运营指标")
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final TicketAnalyticsService ticketAnalyticsService;
    private final SlaAnalyticsService slaAnalyticsService;

    public AnalyticsController(TicketAnalyticsService ticketAnalyticsService,
                               SlaAnalyticsService slaAnalyticsService) {
        this.ticketAnalyticsService = ticketAnalyticsService;
        this.slaAnalyticsService = slaAnalyticsService;
    }

    /** 工单总量卡片：区间创建总数、解决/关闭/取消/当前处理中 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(ticketAnalyticsService.overview(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** 每日新增/解决/关闭，缺失日期补 0 */
    @GetMapping("/tickets/trend")
    public ApiResponse<Map<String, Object>> ticketTrend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(ticketAnalyticsService.trend(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** 类型/状态/优先级分布与优先级处理时长 */
    @GetMapping("/tickets/distribution")
    public ApiResponse<Map<String, Object>> ticketDistribution(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(ticketAnalyticsService.distribution(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** SLA 指标、状态分布、时长桶、优先级对比 */
    @GetMapping("/sla/overview")
    public ApiResponse<Map<String, Object>> slaOverview(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(slaAnalyticsService.overview(
                buildQuery(startDate, endDate, ticketType, priority, assigneeId)));
    }

    /** 每日响应达标率与样本量 */
    @GetMapping("/sla/trend")
    public ApiResponse<Map<String, Object>> slaTrend(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long assigneeId) {
        return ApiResponse.ok(slaAnalyticsService.trend(
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
