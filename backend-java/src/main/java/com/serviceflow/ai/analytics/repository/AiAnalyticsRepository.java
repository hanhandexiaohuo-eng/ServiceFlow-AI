package com.serviceflow.ai.analytics.repository;

import com.serviceflow.ai.analytics.dto.AnalyticsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 效果数据访问：分析记录与建议记录均按 created_at 落区间。
 * 通过 tickets 关联实现工单类型/优先级/处理人筛选，筛选值全部 ? 绑定。
 */
@Repository
public class AiAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiAnalyticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiCallCounts callCounts(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN a.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                       COALESCE(SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count,
                       COALESCE(SUM(CASE WHEN a.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                       AVG(CASE WHEN a.status IN ('SUCCESS','FAILED') AND a.latency_ms IS NOT NULL
                                THEN a.latency_ms END) AS avg_latency
                FROM ai_analyses a
                JOIN tickets t ON t.id = a.ticket_id
                WHERE a.created_at >= ? AND a.created_at < ?
                """ + ticketFilters(query, args);
        AiCallCounts counts = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new AiCallCounts(
                rs.getLong("total"),
                rs.getLong("success_count"),
                rs.getLong("failed_count"),
                rs.getLong("pending_count"),
                nullableDouble(rs, "avg_latency")), args.toArray());
        return counts == null ? new AiCallCounts(0, 0, 0, 0, null) : counts;
    }

    public List<AiFeatureStat> features(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT a.analysis_type AS analysis_type,
                       COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN a.status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                       COALESCE(SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_count,
                       AVG(CASE WHEN a.status IN ('SUCCESS','FAILED') AND a.latency_ms IS NOT NULL
                                THEN a.latency_ms END) AS avg_latency
                FROM ai_analyses a
                JOIN tickets t ON t.id = a.ticket_id
                WHERE a.created_at >= ? AND a.created_at < ?
                """ + ticketFilters(query, args) + """
                GROUP BY a.analysis_type
                ORDER BY total DESC, a.analysis_type ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AiFeatureStat(
                        rs.getString("analysis_type"),
                        rs.getLong("total"),
                        rs.getLong("success_count"),
                        rs.getLong("failed_count"),
                        nullableDouble(rs, "avg_latency")),
                args.toArray());
    }

    public List<AiErrorStat> errors(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT COALESCE(NULLIF(TRIM(a.error_code), ''), 'UNKNOWN') AS error_code,
                       COUNT(*) AS cnt
                FROM ai_analyses a
                JOIN tickets t ON t.id = a.ticket_id
                WHERE a.created_at >= ? AND a.created_at < ? AND a.status = 'FAILED'
                """ + ticketFilters(query, args) + """
                GROUP BY error_code
                ORDER BY cnt DESC, error_code ASC
                LIMIT 10
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new AiErrorStat(
                        rs.getString("error_code"), rs.getLong("cnt")),
                args.toArray());
    }

    public SuggestionCounts suggestionCounts(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN s.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                       COALESCE(SUM(CASE WHEN s.status IN ('ACCEPTED','EXECUTED') THEN 1 ELSE 0 END), 0) AS accepted_count,
                       COALESCE(SUM(CASE WHEN s.status = 'REJECTED' THEN 1 ELSE 0 END), 0) AS rejected_count
                FROM ai_suggestions s
                JOIN tickets t ON t.id = s.ticket_id
                WHERE s.created_at >= ? AND s.created_at < ?
                """ + ticketFilters(query, args);
        SuggestionCounts counts = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new SuggestionCounts(
                rs.getLong("total"),
                rs.getLong("pending_count"),
                rs.getLong("accepted_count"),
                rs.getLong("rejected_count")), args.toArray());
        return counts == null ? new SuggestionCounts(0, 0, 0, 0) : counts;
    }

    public List<SuggestionTypeStat> suggestionByType(AnalyticsQuery query) {
        List<Object> args = new ArrayList<>();
        args.add(query.from());
        args.add(query.toExclusive());
        String sql = """
                SELECT s.suggestion_type AS suggestion_type,
                       COUNT(*) AS total,
                       COALESCE(SUM(CASE WHEN s.status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
                       COALESCE(SUM(CASE WHEN s.status IN ('ACCEPTED','EXECUTED') THEN 1 ELSE 0 END), 0) AS accepted_count,
                       COALESCE(SUM(CASE WHEN s.status = 'REJECTED' THEN 1 ELSE 0 END), 0) AS rejected_count
                FROM ai_suggestions s
                JOIN tickets t ON t.id = s.ticket_id
                WHERE s.created_at >= ? AND s.created_at < ?
                """ + ticketFilters(query, args) + """
                GROUP BY s.suggestion_type
                ORDER BY s.suggestion_type ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SuggestionTypeStat(
                        rs.getString("suggestion_type"),
                        rs.getLong("total"),
                        rs.getLong("pending_count"),
                        rs.getLong("accepted_count"),
                        rs.getLong("rejected_count")),
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

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public record AiCallCounts(long total, long successCount, long failedCount, long pendingCount,
                               Double avgLatencyMs) {
    }

    public record AiFeatureStat(String analysisType, long total, long successCount, long failedCount,
                                Double avgLatencyMs) {
    }

    public record AiErrorStat(String errorCode, long count) {
    }

    public record SuggestionCounts(long total, long pendingCount, long acceptedCount, long rejectedCount) {
    }

    public record SuggestionTypeStat(String suggestionType, long total, long pendingCount,
                                     long acceptedCount, long rejectedCount) {
    }
}
