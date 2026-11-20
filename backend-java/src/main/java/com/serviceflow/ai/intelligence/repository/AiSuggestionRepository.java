package com.serviceflow.ai.intelligence.repository;

import com.serviceflow.ai.intelligence.entity.AiSuggestion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** AI 建议数据访问：状态流转全部使用条件更新，防止重复执行。 */
@Repository
public class AiSuggestionRepository {

    private static final String BASE_SELECT = """
            SELECT s.*, c.name AS created_by_name, d.name AS decided_by_name
            FROM ai_suggestions s
            LEFT JOIN users c ON c.id = s.created_by
            LEFT JOIN users d ON d.id = s.decided_by
            """;

    private static final RowMapper<AiSuggestion> MAPPER = (rs, rowNum) -> {
        AiSuggestion s = new AiSuggestion();
        s.setId(rs.getLong("id"));
        s.setAnalysisId(rs.getLong("analysis_id"));
        s.setTicketId(rs.getLong("ticket_id"));
        s.setSuggestionType(rs.getString("suggestion_type"));
        s.setStatus(rs.getString("status"));
        s.setPayload(rs.getString("payload"));
        s.setSummary(rs.getString("summary"));
        s.setCreatedBy(rs.getLong("created_by"));
        long decidedBy = rs.getLong("decided_by");
        s.setDecidedBy(rs.wasNull() ? null : decidedBy);
        if (rs.getTimestamp("decided_at") != null) {
            s.setDecidedAt(rs.getTimestamp("decided_at").toLocalDateTime());
        }
        s.setDecisionNote(rs.getString("decision_note"));
        s.setExecutionNote(rs.getString("execution_note"));
        s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        s.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        s.setCreatedByName(rs.getString("created_by_name"));
        s.setDecidedByName(rs.getString("decided_by_name"));
        return s;
    };

    private final JdbcTemplate jdbcTemplate;

    public AiSuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiSuggestion insert(AiSuggestion suggestion) {
        String sql = """
                INSERT INTO ai_suggestions
                    (analysis_id, ticket_id, suggestion_type, status, payload, summary, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, suggestion.getAnalysisId());
            ps.setLong(2, suggestion.getTicketId());
            ps.setString(3, suggestion.getSuggestionType());
            ps.setString(4, suggestion.getPayload());
            ps.setString(5, suggestion.getSummary());
            ps.setLong(6, suggestion.getCreatedBy());
            ps.setObject(7, now);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            suggestion.setId(key.longValue());
        }
        return suggestion;
    }

    public Optional<AiSuggestion> findById(Long id) {
        List<AiSuggestion> list = jdbcTemplate.query(BASE_SELECT + " WHERE s.id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<AiSuggestion> findByAnalysisId(Long analysisId) {
        return jdbcTemplate.query(BASE_SELECT + " WHERE s.analysis_id = ? ORDER BY s.id ASC",
                MAPPER, analysisId);
    }

    public List<AiSuggestion> findPendingByTicket(Long ticketId, int limit) {
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE s.ticket_id = ? AND s.status = 'PENDING' ORDER BY s.id DESC LIMIT ?",
                MAPPER, ticketId, limit);
    }

    /** 原子占用待确认建议；返回 0 表示已被处理。 */
    public int claim(Long id, Long decidedBy, String note) {
        return jdbcTemplate.update("""
                        UPDATE ai_suggestions
                        SET status = 'ACCEPTED', decided_by = ?, decided_at = ?, decision_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """,
                decidedBy, LocalDateTime.now(), note, LocalDateTime.now(), id);
    }

    public void markExecuted(Long id, String executionNote) {
        jdbcTemplate.update("""
                        UPDATE ai_suggestions
                        SET status = 'EXECUTED', execution_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'ACCEPTED'
                        """,
                executionNote, LocalDateTime.now(), id);
    }

    public void markAccepted(Long id, String executionNote) {
        jdbcTemplate.update("""
                        UPDATE ai_suggestions
                        SET execution_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'ACCEPTED'
                        """,
                executionNote, LocalDateTime.now(), id);
    }

    public int reject(Long id, Long decidedBy, String note) {
        return jdbcTemplate.update("""
                        UPDATE ai_suggestions
                        SET status = 'REJECTED', decided_by = ?, decided_at = ?, decision_note = ?, updated_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """,
                decidedBy, LocalDateTime.now(), note, LocalDateTime.now(), id);
    }

    /** 业务执行失败时释放占用，允许人工重试。 */
    public void revertToPending(Long id) {
        jdbcTemplate.update("""
                        UPDATE ai_suggestions
                        SET status = 'PENDING', decided_by = NULL, decided_at = NULL,
                            decision_note = NULL, updated_at = ?
                        WHERE id = ? AND status = 'ACCEPTED'
                        """,
                LocalDateTime.now(), id);
    }
}
