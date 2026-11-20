package com.serviceflow.ai.analytics.repository;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能调度数据访问：只读取 dispatch_logs 与 tickets，不解析 detail JSON。
 *
 * 工单级口径：取区间内该工单最后一条 RECOMMEND，再查找其后的 ASSIGN 记录，
 * 是否采纳由 service 依据 agent_id 判定（日志侧的 agent_id 可能为 NULL）。
 */
@Repository
public class DispatchAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public DispatchAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 区间内 RECOMMEND 日志行数（不是工单数）。 */
    public long recommendationCount(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT COUNT(*)
                FROM dispatch_logs d
                JOIN tickets t ON t.id = d.ticket_id
                WHERE d.action = 'RECOMMEND' AND d.created_at >= ? AND d.created_at < ?
                """ + ticketFilters(query, args);
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    /**
     * 每个「区间内最后一条 RECOMMEND」工单与其后的 ASSIGN 组合。
     * 没有后续 ASSIGN 的工单返回一行 assign_* 为 null 的记录。
     */
    public List<DispatchDecisionRow> decisionRows(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT r.ticket_id AS ticket_id,
                       t.ticket_no AS ticket_no,
                       t.priority AS priority,
                       r.created_at AS recommend_at,
                       r.agent_id AS recommend_agent_id,
                       ru.name AS recommend_agent_name,
                       a.agent_id AS assign_agent_id,
                       a.agent_name AS assign_agent_name,
                       a.created_at AS assign_at
                FROM (
                    SELECT d.id, d.ticket_id, d.agent_id, d.created_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY d.ticket_id
                               ORDER BY d.created_at DESC, d.id DESC
                           ) AS rn
                    FROM dispatch_logs d
                    WHERE d.action = 'RECOMMEND' AND d.created_at >= ? AND d.created_at < ?
                ) r
                JOIN tickets t ON t.id = r.ticket_id
                LEFT JOIN users ru ON ru.id = r.agent_id
                LEFT JOIN dispatch_logs a
                       ON a.ticket_id = r.ticket_id
                      AND a.action = 'ASSIGN'
                      AND (a.created_at > r.created_at
                           OR (a.created_at = r.created_at AND a.id > r.id))
                WHERE r.rn = 1
                """ + ticketFilters(query, args) + """
                ORDER BY r.ticket_id ASC, a.created_at ASC, a.id ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DispatchDecisionRow(
                        rs.getLong("ticket_id"),
                        rs.getString("ticket_no"),
                        rs.getString("priority"),
                        toLocalDateTime(rs, "recommend_at"),
                        nullableLong(rs, "recommend_agent_id"),
                        rs.getString("recommend_agent_name"),
                        nullableLong(rs, "assign_agent_id"),
                        rs.getString("assign_agent_name"),
                        toLocalDateTime(rs, "assign_at")),
                args.toArray());
    }

    /** 区间内 ASSIGN 工单的必需技能覆盖情况（按当前技能配置，不是调度时快照）。 */
    public List<SkillCheck> skillChecks(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT
                    (SELECT COUNT(*)
                       FROM ticket_type_skill_rules r
                      WHERE r.ticket_type = t.type AND r.required = 1) AS required_count,
                    (SELECT COUNT(*)
                       FROM ticket_type_skill_rules r
                       JOIN agent_skill_relations rel
                         ON rel.skill_id = r.skill_id AND rel.agent_id = d.agent_id
                      WHERE r.ticket_type = t.type AND r.required = 1) AS matched_count
                FROM dispatch_logs d
                JOIN tickets t ON t.id = d.ticket_id
                WHERE d.action = 'ASSIGN' AND d.created_at >= ? AND d.created_at < ?
                """ + ticketFilters(query, args);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SkillCheck(
                        rs.getLong("required_count"), rs.getLong("matched_count")),
                args.toArray());
    }

    private String ticketFilters(AnalyticsQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        if (query.ticketType() != null) {
            sql.append(" AND t.type = ?");
            args.add(query.ticketType());
        }
        if (query.priority() != null) {
            sql.append(" AND t.priority = ?");
            args.add(query.priority());
        }
        if (query.assigneeId() != null) {
            sql.append(" AND t.assignee_id = ?");
            args.add(query.assigneeId());
        }
        return sql.toString();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record DispatchDecisionRow(long ticketId, String ticketNo, String priority,
                                      LocalDateTime recommendAt, Long recommendAgentId,
                                      String recommendAgentName, Long assignAgentId,
                                      String assignAgentName, LocalDateTime assignAt) {
    }

    public record SkillCheck(long requiredCount, long matchedCount) {
    }
}
