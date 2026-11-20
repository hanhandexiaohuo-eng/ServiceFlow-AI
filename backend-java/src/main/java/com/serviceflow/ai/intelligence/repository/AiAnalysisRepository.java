package com.serviceflow.ai.intelligence.repository;

import com.serviceflow.ai.intelligence.entity.AiAnalysis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** AI 分析记录数据访问。 */
@Repository
public class AiAnalysisRepository {

    private static final String BASE_SELECT = """
            SELECT a.*, t.ticket_no, u.name AS requested_by_name
            FROM ai_analyses a
            JOIN tickets t ON t.id = a.ticket_id
            LEFT JOIN users u ON u.id = a.requested_by
            """;

    private static final RowMapper<AiAnalysis> MAPPER = (rs, rowNum) -> {
        AiAnalysis a = new AiAnalysis();
        a.setId(rs.getLong("id"));
        a.setTicketId(rs.getLong("ticket_id"));
        long sessionId = rs.getLong("session_id");
        a.setSessionId(rs.wasNull() ? null : sessionId);
        a.setAnalysisType(rs.getString("analysis_type"));
        a.setStatus(rs.getString("status"));
        a.setRequestedBy(rs.getLong("requested_by"));
        a.setRequestNote(rs.getString("request_note"));
        a.setPromptVersion(rs.getString("prompt_version"));
        a.setModel(rs.getString("model"));
        int latency = rs.getInt("latency_ms");
        a.setLatencyMs(rs.wasNull() ? null : latency);
        a.setTicketSnapshot(rs.getString("ticket_snapshot"));
        a.setResultJson(rs.getString("result_json"));
        a.setErrorCode(rs.getString("error_code"));
        a.setErrorMessage(rs.getString("error_message"));
        a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        if (rs.getTimestamp("completed_at") != null) {
            a.setCompletedAt(rs.getTimestamp("completed_at").toLocalDateTime());
        }
        a.setRequestedByName(rs.getString("requested_by_name"));
        a.setTicketNo(rs.getString("ticket_no"));
        return a;
    };

    private final JdbcTemplate jdbcTemplate;

    public AiAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiAnalysis insert(AiAnalysis analysis) {
        String sql = """
                INSERT INTO ai_analyses
                    (ticket_id, session_id, analysis_type, status, requested_by, request_note,
                     ticket_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, analysis.getTicketId());
            if (analysis.getSessionId() == null) {
                ps.setObject(2, null);
            } else {
                ps.setLong(2, analysis.getSessionId());
            }
            ps.setString(3, analysis.getAnalysisType());
            ps.setString(4, analysis.getStatus());
            ps.setLong(5, analysis.getRequestedBy());
            ps.setString(6, analysis.getRequestNote());
            ps.setString(7, analysis.getTicketSnapshot());
            ps.setObject(8, LocalDateTime.now());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            analysis.setId(key.longValue());
        }
        return analysis;
    }

    public void markSuccess(Long id, String promptVersion, String model, Integer latencyMs, String resultJson) {
        jdbcTemplate.update("""
                        UPDATE ai_analyses
                        SET status = 'SUCCESS', prompt_version = ?, model = ?, latency_ms = ?,
                            result_json = ?, error_code = NULL, error_message = NULL, completed_at = ?
                        WHERE id = ?
                        """,
                promptVersion, model, latencyMs, resultJson, LocalDateTime.now(), id);
    }

    public void markFailed(Long id, String errorCode, String errorMessage, Integer latencyMs) {
        jdbcTemplate.update("""
                        UPDATE ai_analyses
                        SET status = 'FAILED', error_code = ?, error_message = ?, latency_ms = ?,
                            completed_at = ?
                        WHERE id = ?
                        """,
                errorCode, errorMessage, latencyMs, LocalDateTime.now(), id);
    }

    public Optional<AiAnalysis> findById(Long id) {
        List<AiAnalysis> list = jdbcTemplate.query(BASE_SELECT + " WHERE a.id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<AiAnalysis> findPage(Long ticketId, String type, String status, Long requestedBy,
                                     int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(ticketId, type, status, requestedBy, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                BASE_SELECT + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
    }

    public long count(Long ticketId, String type, String status, Long requestedBy) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(ticketId, type, status, requestedBy, args);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analyses a " + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String buildWhere(Long ticketId, String type, String status, Long requestedBy, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (ticketId != null) {
            conditions.add("a.ticket_id = ?");
            args.add(ticketId);
        }
        if (type != null && !type.isBlank()) {
            conditions.add("a.analysis_type = ?");
            args.add(type);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("a.status = ?");
            args.add(status);
        }
        if (requestedBy != null) {
            conditions.add("a.requested_by = ?");
            args.add(requestedBy);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    public Optional<AiAnalysis> findRecentSuccess(Long ticketId, String type, Long requestedBy,
                                                  LocalDateTime since) {
        List<AiAnalysis> list = jdbcTemplate.query(BASE_SELECT + """
                         WHERE a.ticket_id = ? AND a.analysis_type = ? AND a.requested_by = ?
                           AND a.status = 'SUCCESS' AND a.created_at >= ?
                         ORDER BY a.id DESC LIMIT 1
                        """,
                MAPPER, ticketId, type, requestedBy, since);
        return list.stream().findFirst();
    }

    public List<AiAnalysis> findBySessionId(Long sessionId, int limit) {
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE a.session_id = ? ORDER BY a.id DESC LIMIT ?",
                MAPPER, sessionId, limit);
    }

    public List<AiAnalysis> findLatestByTicket(Long ticketId, String type, int limit) {
        if (type == null || type.isBlank()) {
            return jdbcTemplate.query(
                    BASE_SELECT + " WHERE a.ticket_id = ? ORDER BY a.id DESC LIMIT ?",
                    MAPPER, ticketId, limit);
        }
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE a.ticket_id = ? AND a.analysis_type = ? ORDER BY a.id DESC LIMIT ?",
                MAPPER, ticketId, type, limit);
    }
}
