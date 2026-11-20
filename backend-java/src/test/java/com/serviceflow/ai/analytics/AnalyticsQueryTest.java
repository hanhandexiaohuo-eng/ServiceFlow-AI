package com.serviceflow.ai.analytics;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一查询：默认 30 天、区间校验消息、半开区间边界与枚举归一化。 */
class AnalyticsQueryTest {

    @Test
    void defaultRangeIsLast30DaysEndingToday() {
        LocalDate today = LocalDate.now();
        AnalyticsQuery query = new AnalyticsQuery(null, null, null, null, null);

        assertEquals(today, query.endDate());
        assertEquals(today.minusDays(29), query.startDate());
        assertEquals(today.minusDays(29).atStartOfDay(), query.from());
        assertEquals(today.plusDays(1).atStartOfDay(), query.toExclusive());
    }

    @Test
    void startDateAfterEndDateIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AnalyticsQuery(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 1),
                        null, null, null));

        assertEquals("开始日期不能晚于结束日期", error.getMessage());
    }

    @Test
    void spanOf366DaysIsAllowedBut367DaysIsRejected() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertDoesNotThrow(() -> new AnalyticsQuery(start, start.plusDays(366), null, null, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AnalyticsQuery(start, start.plusDays(367), null, null, null));

        assertEquals("统计范围不能超过 366 天", error.getMessage());
    }

    @Test
    void illegalTicketTypeAndPriorityAreRejected() {
        IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> new AnalyticsQuery(null, null, "FOO", null, null));
        assertEquals("非法工单类型", typeError.getMessage());

        IllegalArgumentException priorityError = assertThrows(IllegalArgumentException.class,
                () -> new AnalyticsQuery(null, null, null, "BAD", null));
        assertEquals("非法优先级", priorityError.getMessage());
    }

    @Test
    void validEnumsAreTrimmedAndNormalizedToUpperCase() {
        AnalyticsQuery query = new AnalyticsQuery(null, null, " incident ", "high", null);

        assertEquals("INCIDENT", query.ticketType());
        assertEquals("HIGH", query.priority());
        assertNull(new AnalyticsQuery(null, null, "   ", null, null).ticketType());
    }

    @Test
    void singleDayRangeIncludesEndDateAndExcludesNextMidnight() {
        LocalDate day = LocalDate.of(2026, 11, 16);
        AnalyticsQuery query = new AnalyticsQuery(day, day, null, null, null);

        assertEquals(day.atStartOfDay(), query.from());
        assertEquals(LocalDateTime.of(2026, 11, 17, 0, 0), query.toExclusive());
        // endDate 当天最后一刻仍在区间内，次日 0 点被排除
        assertTrue(LocalDateTime.of(2026, 11, 16, 23, 59, 59, 999_999_999).isBefore(query.toExclusive()));
        assertTrue(LocalDateTime.of(2026, 11, 17, 0, 0).equals(query.toExclusive()));
    }
}
