package com.serviceflow.ai.analytics.repository;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营分析数据访问：所有指标均由数据库聚合得出，不在这里做任何补偿或拟合。
 *
 * 安全约定：SQL 中只出现本类内部固定的列名与片段，用户输入一律走 ? 占位符绑定；
 * 可选筛选项通过白名单字段拼接，绝不接受外部传入的列名/表名。
 *
 * SLA 统计统一在 WHERE 中排除 status='CANCELLED'（取消工单不计入任何 SLA 分母），
 * 工单运营统计则保留取消工单用于总量与状态分布。
 */
@Repository
public class AnalyticsRepository {

    /**
     * SLA 有效截止时间：优先取持久化的 sla_due_at；历史缺失时按 TicketSlaService
     * 的 4/8/24/72 小时策略兜底，保证「状态分布」与实时工单详情口径一致。
     */
    private static final String EFFECTIVE_DUE = """
            COALESCE(sla_due_at, DATE_ADD(created_at,
                INTERVAL CASE priority
                    WHEN 'URGENT' THEN 4
                    WHEN 'HIGH' THEN 8
                    WHEN 'MEDIUM' THEN 24
                    ELSE 72
                END HOUR))
            """;

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 百分比统一保留 1 位小数 */
    public static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // ---------------------------------------------------------------- 工单运营

    public Map<String, Long> overviewCounts(AnalyticsQuery query) {
        LocalDateTime from = query.from();
        LocalDateTime to = query.toExclusive();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN status = 'RESOLVED' AND resolved_at >= ? AND resolved_at < ?
                                         THEN 1 ELSE 0 END), 0) AS resolved,
                       COALESCE(SUM(CASE WHEN status = 'CLOSED' AND closed_at >= ? AND closed_at < ?
                                         THEN 1 ELSE 0 END), 0) AS closed,
                       COALESCE(SUM(CASE WHEN status = 'CANCELLED' AND cancelled_at >= ? AND cancelled_at < ?
                                         THEN 1 ELSE 0 END), 0) AS cancelled
                FROM tickets
                WHERE created_at >= ? AND created_at < ?
                """);
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        args.add(from);
        args.add(to);
        sql.append(filters(query, args));

        Map<String, Long> counts = jdbcTemplate.queryForObject(sql.toString(), (rs, rowNum) -> {
            Map<String, Long> row = new LinkedHashMap<>();
            row.put("total", rs.getLong("total"));
            row.put("resolved", rs.getLong("resolved"));
            row.put("closed", rs.getLong("closed"));
            row.put("cancelled", rs.getLong("cancelled"));
            return row;
        }, args.toArray());

        List<Object> currentArgs = new ArrayList<>();
        String currentSql = "SELECT COUNT(*) FROM tickets"
                + " WHERE status IN ('ASSIGNED','ACCEPTED','PROCESSING')"
                + filters(query, currentArgs);
        Long processing = jdbcTemplate.queryForObject(currentSql, Long.class, currentArgs.toArray());

        Map<String, Long> result = counts == null ? new LinkedHashMap<>() : counts;
        result.put("processing", processing == null ? 0L : processing);
        return result;
    }

    public TrendCounts dailyTrend(AnalyticsQuery query) {
        return new TrendCounts(
                dailyCounts("created_at", query, null),
                dailyCounts("resolved_at", query, "status = 'RESOLVED'"),
                dailyCounts("closed_at", query, "status = 'CLOSED'"));
    }

    public List<GroupCount> distributionByType(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT CASE WHEN type IS NULL OR TRIM(type) = '' THEN 'UNCLASSIFIED' ELSE type END AS group_key,
                       COUNT(*) AS cnt
                FROM tickets
                WHERE created_at >= ? AND created_at < ?
                """ + filters(query, args) + " GROUP BY group_key ORDER BY cnt DESC, group_key ASC";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new GroupCount(rs.getString("group_key"), rs.getLong("cnt")),
                args.toArray());
    }

    public List<GroupCount> distributionByStatus(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT status AS group_key, COUNT(*) AS cnt
                FROM tickets
                WHERE created_at >= ? AND created_at < ?
                """ + filters(query, args) + " GROUP BY status ORDER BY cnt DESC, group_key ASC";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new GroupCount(rs.getString("group_key"), rs.getLong("cnt")),
                args.toArray());
    }

    public List<PriorityStat> priorityStats(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT priority,
                       COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END), 0) AS resolved,
                       COALESCE(SUM(CASE WHEN status <> 'CANCELLED'
                                          AND sla_due_at IS NOT NULL
                                          AND COALESCE(resolved_at, closed_at, NOW()) > sla_due_at
                                     THEN 1 ELSE 0 END), 0) AS overdue,
                       AVG(CASE WHEN status IN ('RESOLVED','CLOSED')
                                  AND COALESCE(resolved_at, closed_at) IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, created_at, COALESCE(resolved_at, closed_at))
                           END) AS avg_handle_minutes
                FROM tickets
                WHERE created_at >= ? AND created_at < ?
                """ + filters(query, args) + " GROUP BY priority";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PriorityStat(
                        rs.getString("priority"),
                        rs.getLong("total"),
                        rs.getLong("resolved"),
                        rs.getLong("overdue"),
                        nullableDouble(rs, "avg_handle_minutes")),
                args.toArray());
    }

    // ---------------------------------------------------------------- SLA 效率

    public SlaAggregate slaAggregate(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                  THEN 1 ELSE 0 END), 0) AS d_resp,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                       AND first_response_at IS NOT NULL
                                       AND first_response_at <= sla_due_at
                                  THEN 1 ELSE 0 END), 0) AS n_resp,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND status IN ('RESOLVED','CLOSED')
                                       AND COALESCE(resolved_at, closed_at) IS NOT NULL
                                  THEN 1 ELSE 0 END), 0) AS d_resolve,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND status IN ('RESOLVED','CLOSED')
                                       AND COALESCE(resolved_at, closed_at) IS NOT NULL
                                       AND COALESCE(resolved_at, closed_at) <= sla_due_at
                                  THEN 1 ELSE 0 END), 0) AS n_resolve,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND status IN ('RESOLVED','CLOSED')
                                       AND COALESCE(resolved_at, closed_at) IS NOT NULL
                                       AND COALESCE(resolved_at, closed_at) > sla_due_at
                                  THEN 1 ELSE 0 END), 0) AS overdue_completed,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NULL THEN 1 ELSE 0 END), 0) AS missing_sla,
                    AVG(CASE WHEN first_response_at IS NOT NULL
                             THEN TIMESTAMPDIFF(MINUTE, created_at, first_response_at)
                        END) AS avg_first_response,
                    AVG(CASE WHEN status IN ('RESOLVED','CLOSED')
                              AND COALESCE(resolved_at, closed_at) IS NOT NULL
                             THEN TIMESTAMPDIFF(MINUTE, created_at, COALESCE(resolved_at, closed_at))
                        END) AS avg_resolution
                FROM tickets
                WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                """ + filters(query, args);

        SlaRangeAggregate aggregate = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new SlaRangeAggregate(
                rs.getLong("d_resp"),
                rs.getLong("n_resp"),
                rs.getLong("d_resolve"),
                rs.getLong("n_resolve"),
                rs.getLong("overdue_completed"),
                rs.getLong("missing_sla"),
                nullableDouble(rs, "avg_first_response"),
                nullableDouble(rs, "avg_resolution")), args.toArray());

        long[] current = currentSlaCounts(query);
        return new SlaAggregate(
                aggregate.dResp(),
                aggregate.nResp(),
                aggregate.dResolve(),
                aggregate.nResolve(),
                current[0],
                current[1],
                aggregate.overdueCompleted(),
                aggregate.missingSla(),
                aggregate.avgFirstResponseMinutes(),
                aggregate.avgResolutionMinutes());
    }

    /** 当前活动工单的预警/超时数量，不受统计区间影响 */
    private long[] currentSlaCounts(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        String sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                       AND TIMESTAMPDIFF(MINUTE, NOW(), sla_due_at) BETWEEN 0 AND 120
                                  THEN 1 ELSE 0 END), 0) AS warning_count,
                    COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL AND NOW() > sla_due_at
                                  THEN 1 ELSE 0 END), 0) AS overdue_count
                FROM tickets
                WHERE status IN ('PENDING','ASSIGNED','ACCEPTED','PROCESSING')
                """ + filters(query, args);
        long[] counts = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new long[]{
                rs.getLong("warning_count"), rs.getLong("overdue_count")}, args.toArray());
        return counts == null ? new long[]{0L, 0L} : counts;
    }

    public List<StatusCount> slaStatusDistribution(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT state, COUNT(*) AS cnt
                FROM (
                    SELECT CASE
                               WHEN status IN ('RESOLVED','CLOSED') THEN 'COMPLETED'
                               WHEN NOW() > %s THEN 'OVERDUE'
                               WHEN TIMESTAMPDIFF(MINUTE, NOW(), %s) <= 120 THEN 'WARNING'
                               ELSE 'ON_TRACK'
                           END AS state
                    FROM tickets
                    WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                    %s
                ) t
                GROUP BY state
                """.formatted(EFFECTIVE_DUE, EFFECTIVE_DUE, filters(query, args));
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new StatusCount(rs.getString("state"), rs.getLong("cnt")),
                args.toArray());
    }

    public List<BucketCount> firstResponseBuckets(AnalyticsQuery query) {
        return durationBuckets(query, false);
    }

    public List<BucketCount> resolutionBuckets(AnalyticsQuery query) {
        return durationBuckets(query, true);
    }

    public List<PrioritySlaStat> slaPriorityComparison(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT priority,
                       COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                          AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                     THEN 1 ELSE 0 END), 0) AS denominator,
                       COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                          AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                          AND first_response_at IS NOT NULL
                                          AND first_response_at <= sla_due_at
                                     THEN 1 ELSE 0 END), 0) AS numerator,
                       AVG(CASE WHEN first_response_at IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, created_at, first_response_at)
                           END) AS avg_first_response,
                       COALESCE(SUM(CASE WHEN status IN ('PENDING','ASSIGNED','ACCEPTED','PROCESSING')
                                          AND sla_due_at IS NOT NULL
                                          AND NOW() > sla_due_at
                                     THEN 1 ELSE 0 END), 0) AS overdue
                FROM tickets
                WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                """ + filters(query, args) + " GROUP BY priority";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new PrioritySlaStat(
                        rs.getString("priority"),
                        rs.getLong("denominator"),
                        rs.getLong("numerator"),
                        nullableDouble(rs, "avg_first_response"),
                        rs.getLong("overdue")),
                args.toArray());
    }

    public List<DailySlaCount> slaDailyTrend(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT DATE(created_at) AS d,
                       COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                          AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                     THEN 1 ELSE 0 END), 0) AS denominator,
                       COALESCE(SUM(CASE WHEN sla_due_at IS NOT NULL
                                          AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                          AND first_response_at IS NOT NULL
                                          AND first_response_at <= sla_due_at
                                     THEN 1 ELSE 0 END), 0) AS numerator
                FROM tickets
                WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                """ + filters(query, args) + " GROUP BY DATE(created_at) ORDER BY d";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DailySlaCount(
                        rs.getDate("d").toLocalDate(),
                        rs.getLong("denominator"),
                        rs.getLong("numerator")),
                args.toArray());
    }

    // ---------------------------------------------------------------- 内部实现

    private List<DailyCount> dailyCounts(String timeColumn, AnalyticsQuery query, String extraCondition) {
        // timeColumn / extraCondition 均为本类内部固定值，不来自请求参数
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        StringBuilder sql = new StringBuilder("SELECT DATE(").append(timeColumn)
                .append(") AS d, COUNT(*) AS cnt FROM tickets WHERE ")
                .append(timeColumn).append(" >= ? AND ").append(timeColumn).append(" < ?");
        if (extraCondition != null) {
            sql.append(" AND ").append(extraCondition);
        }
        sql.append(filters(query, args));
        sql.append(" GROUP BY DATE(").append(timeColumn).append(") ORDER BY d");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new DailyCount(rs.getDate("d").toLocalDate(), rs.getLong("cnt")),
                args.toArray());
    }

    private List<BucketCount> durationBuckets(AnalyticsQuery query, boolean resolution) {
        String durationExpr = resolution
                ? "TIMESTAMPDIFF(MINUTE, created_at, COALESCE(resolved_at, closed_at))"
                : "TIMESTAMPDIFF(MINUTE, created_at, first_response_at)";
        String extraCondition = resolution
                ? " AND status IN ('RESOLVED','CLOSED') AND COALESCE(resolved_at, closed_at) IS NOT NULL"
                : " AND first_response_at IS NOT NULL";
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT CASE
                           WHEN m < 60 THEN '<60'
                           WHEN m < 240 THEN '60-240'
                           WHEN m < 480 THEN '240-480'
                           WHEN m < 1440 THEN '480-1440'
                           WHEN m <= 4320 THEN '1440-4320'
                           ELSE '>4320'
                       END AS bucket,
                       COUNT(*) AS cnt
                FROM (
                    SELECT %s AS m
                    FROM tickets
                    WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'%s%s
                ) t
                GROUP BY bucket
                """.formatted(durationExpr, extraCondition, filters(query, args));
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new BucketCount(rs.getString("bucket"), rs.getLong("cnt")),
                args.toArray());
    }

    /** 白名单筛选项：只追加固定列名，值全部绑定 */
    private String filters(AnalyticsQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        if (query.ticketType() != null) {
            sql.append(" AND type = ?");
            args.add(query.ticketType());
        }
        if (query.priority() != null) {
            sql.append(" AND priority = ?");
            args.add(query.priority());
        }
        if (query.assigneeId() != null) {
            sql.append(" AND assignee_id = ?");
            args.add(query.assigneeId());
        }
        return sql.toString();
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    // ---------------------------------------------------------------- 行结构

    public record GroupCount(String key, long count) {
    }

    public record DailyCount(LocalDate date, long count) {
    }

    public record TrendCounts(List<DailyCount> created, List<DailyCount> resolved, List<DailyCount> closed) {
    }

    public record PriorityStat(String priority, long total, long resolved, long overdue,
                               Double avgHandleMinutes) {
    }

    public record SlaAggregate(long dResp, long nResp, long dResolve, long nResolve,
                               long warningCount, long overdueCount, long overdueCompleted,
                               long missingSla, Double avgFirstResponseMinutes,
                               Double avgResolutionMinutes) {
    }

    public record StatusCount(String state, long count) {
    }

    /** SLA 区间聚合的中间结果，预警/超时来自独立的实时查询 */
    private record SlaRangeAggregate(long dResp, long nResp, long dResolve, long nResolve,
                                     long overdueCompleted, long missingSla,
                                     Double avgFirstResponseMinutes, Double avgResolutionMinutes) {
    }

    public record BucketCount(String bucket, long count) {
    }

    public record PrioritySlaStat(String priority, long denominator, long numerator,
                                  Double avgFirstResponseMinutes, long overdue) {
    }

    public record DailySlaCount(LocalDate date, long denominator, long numerator) {
    }
}
