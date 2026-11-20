package com.serviceflow.ai.analytics.dto;

import com.serviceflow.ai.ticket.enums.Priority;
import com.serviceflow.ai.ticket.enums.TicketType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 运营分析统一查询条件：所有统计接口共用同一套区间与筛选口径，
 * 默认值与校验集中在这里，避免各 service 各写一份导致口径漂移。
 */
public record AnalyticsQuery(LocalDate startDate, LocalDate endDate, String ticketType,
                             String priority, Long assigneeId) {

    /** 单次统计允许的最长跨度，防止一次扫描过多历史数据 */
    private static final int MAX_SPAN_DAYS = 366;

    public AnalyticsQuery {
        LocalDate end = endDate == null ? LocalDate.now() : endDate;
        LocalDate start = startDate == null ? end.minusDays(29) : startDate;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("统计范围不能超过 366 天");
        }
        ticketType = normalizeEnum(ticketType, TicketType.values(), "非法工单类型");
        priority = normalizeEnum(priority, Priority.values(), "非法优先级");
        startDate = start;
        endDate = end;
    }

    /** 区间起点（含） */
    public LocalDateTime from() {
        return startDate.atStartOfDay();
    }

    /** 区间终点（不含），即 endDate 次日 00:00 */
    public LocalDateTime toExclusive() {
        return endDate.plusDays(1).atStartOfDay();
    }

    private static <E extends Enum<E>> String normalizeEnum(String value, E[] allowed, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (E candidate : allowed) {
            if (candidate.name().equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException(message);
    }
}
