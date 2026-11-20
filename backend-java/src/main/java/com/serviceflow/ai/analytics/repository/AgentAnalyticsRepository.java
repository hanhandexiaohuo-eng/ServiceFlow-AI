package com.serviceflow.ai.analytics.repository;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 人员绩效数据访问：人员集合取自 users.role='AGENT'（含禁用账号），工单归属按 tickets.assignee_id。
 *
 * 安全约定：可选筛选项只拼接本类内部的固定列名，用户输入一律 ? 绑定；
 * 不接收表名、列名或 SQL 片段。
 */
@Repository
public class AgentAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 每个人员在本区间的绩效原始聚合；无工单的人员也会出现（计数为 0）。 */
    public List<AgentStatRow> agentStats(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String ticketFilters = ticketFilters(query, args);

        List<Object> occupiedArgs = new ArrayList<>();
        String occupiedFilters = ticketFilters(query, occupiedArgs);

        String sql = """
                SELECT u.id AS agent_id,
                       u.name AS agent_name,
                       u.username AS username,
                       u.status AS status,
                       p.max_concurrent AS max_concurrent,
                       COALESCE(s.assigned_total, 0) AS assigned_total,
                       COALESCE(s.resolved_total, 0) AS resolved_total,
                       s.avg_first_response AS avg_first_response,
                       s.avg_resolution AS avg_resolution,
                       COALESCE(s.sla_numerator, 0) AS sla_numerator,
                       COALESCE(s.sla_denominator, 0) AS sla_denominator,
                       COALESCE(s.overdue_total, 0) AS overdue_total,
                       COALESCE(s.weighted_resolved, 0) AS weighted_resolved,
                       COALESCE(o.current_occupied, 0) AS current_occupied
                FROM users u
                LEFT JOIN agent_profiles p ON p.user_id = u.id
                LEFT JOIN (
                    SELECT assignee_id,
                           COUNT(*) AS assigned_total,
                           SUM(CASE WHEN status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END) AS resolved_total,
                           AVG(CASE WHEN first_response_at IS NOT NULL
                                    THEN TIMESTAMPDIFF(MINUTE, created_at, first_response_at)
                               END) AS avg_first_response,
                           AVG(CASE WHEN status IN ('RESOLVED','CLOSED')
                                     AND COALESCE(resolved_at, closed_at) IS NOT NULL
                                    THEN TIMESTAMPDIFF(MINUTE, created_at, COALESCE(resolved_at, closed_at))
                               END) AS avg_resolution,
                           SUM(CASE WHEN sla_due_at IS NOT NULL
                                     AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                     AND first_response_at IS NOT NULL
                                     AND first_response_at <= sla_due_at
                                THEN 1 ELSE 0 END) AS sla_numerator,
                           SUM(CASE WHEN sla_due_at IS NOT NULL
                                     AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                THEN 1 ELSE 0 END) AS sla_denominator,
                           SUM(CASE WHEN sla_due_at IS NOT NULL
                                     AND COALESCE(resolved_at, closed_at, NOW()) > sla_due_at
                                THEN 1 ELSE 0 END) AS overdue_total,
                           SUM(CASE WHEN status IN ('RESOLVED','CLOSED')
                                    THEN CASE priority
                                             WHEN 'URGENT' THEN 4
                                             WHEN 'HIGH' THEN 3
                                             WHEN 'MEDIUM' THEN 2
                                             WHEN 'LOW' THEN 1
                                             ELSE 0
                                         END
                                    ELSE 0 END) AS weighted_resolved
                    FROM tickets
                    WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                    %s
                    GROUP BY assignee_id
                ) s ON s.assignee_id = u.id
                LEFT JOIN (
                    SELECT assignee_id, COUNT(*) AS current_occupied
                    FROM tickets
                    WHERE status IN ('ASSIGNED','ACCEPTED','PROCESSING')
                    %s
                    GROUP BY assignee_id
                ) o ON o.assignee_id = u.id
                WHERE u.role = 'AGENT'
                ORDER BY u.id ASC
                """.formatted(ticketFilters, occupiedFilters);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new AgentStatRow(
                        rs.getLong("agent_id"),
                        rs.getString("agent_name"),
                        rs.getString("username"),
                        rs.getInt("status"),
                        nullableInteger(rs, "max_concurrent"),
                        rs.getLong("assigned_total"),
                        rs.getLong("resolved_total"),
                        rs.getLong("current_occupied"),
                        nullableDouble(rs, "avg_first_response"),
                        nullableDouble(rs, "avg_resolution"),
                        rs.getLong("sla_numerator"),
                        rs.getLong("sla_denominator"),
                        rs.getLong("overdue_total"),
                        rs.getLong("weighted_resolved")),
                args.toArray());
    }

    /** 按工单创建日期分组的人员绩效趋势；agentId 为空时为全团队汇总。 */
    public List<DailyAgentStat> agentTrend(AnalyticsQuery query, Long agentId) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT DATE(created_at) AS d,
                       SUM(CASE WHEN status IN ('RESOLVED','CLOSED') THEN 1 ELSE 0 END) AS resolved,
                       AVG(CASE WHEN first_response_at IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, created_at, first_response_at)
                           END) AS avg_first_response,
                       SUM(CASE WHEN sla_due_at IS NOT NULL
                                 AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                                 AND first_response_at IS NOT NULL
                                 AND first_response_at <= sla_due_at
                            THEN 1 ELSE 0 END) AS sla_numerator,
                       SUM(CASE WHEN sla_due_at IS NOT NULL
                                 AND (first_response_at IS NOT NULL OR NOW() > sla_due_at)
                            THEN 1 ELSE 0 END) AS sla_denominator
                FROM tickets
                WHERE created_at >= ? AND created_at < ? AND status <> 'CANCELLED'
                """ + ticketFilters(query, args)
                + (agentId == null ? "" : " AND assignee_id = ?")
                + " GROUP BY DATE(created_at) ORDER BY d";
        if (agentId != null) {
            args.add(agentId);
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DailyAgentStat(
                        rs.getDate("d").toLocalDate(),
                        rs.getLong("resolved"),
                        nullableDouble(rs, "avg_first_response"),
                        rs.getLong("sla_numerator"),
                        rs.getLong("sla_denominator")),
                args.toArray());
    }

    /** 白名单筛选项：只追加固定列名，值全部绑定。 */
    private String ticketFilters(AnalyticsQuery query, List<Object> args) {
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

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record AgentStatRow(long agentId, String agentName, String username, int status,
                               Integer maxConcurrent, long assignedTotal, long resolvedTotal,
                               long currentOccupied, Double avgFirstResponseMinutes,
                               Double avgResolutionMinutes, long slaNumerator, long slaDenominator,
                               long overdueTotal, long weightedResolved) {
    }

    public record DailyAgentStat(LocalDate date, long resolved, Double avgFirstResponseMinutes,
                                 long slaNumerator, long slaDenominator) {
    }
}
