package com.serviceflow.ai.intelligence.repository;

import com.serviceflow.ai.intelligence.entity.AiMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** AI 会话消息数据访问。 */
@Repository
public class AiMessageRepository {

    private static final RowMapper<AiMessage> MAPPER = (rs, rowNum) -> {
        AiMessage m = new AiMessage();
        m.setId(rs.getLong("id"));
        m.setSessionId(rs.getLong("session_id"));
        m.setRole(rs.getString("role"));
        m.setContent(rs.getString("content"));
        long analysisId = rs.getLong("analysis_id");
        m.setAnalysisId(rs.wasNull() ? null : analysisId);
        m.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        return m;
    };

    private final JdbcTemplate jdbcTemplate;

    public AiMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiMessage insert(AiMessage message) {
        String sql = "INSERT INTO ai_messages (session_id, role, content, analysis_id) VALUES (?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, message.getSessionId());
            ps.setString(2, message.getRole());
            ps.setString(3, message.getContent());
            if (message.getAnalysisId() == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, message.getAnalysisId());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            message.setId(key.longValue());
        }
        return message;
    }

    public List<AiMessage> findBySessionId(Long sessionId) {
        return jdbcTemplate.query(
                "SELECT * FROM ai_messages WHERE session_id = ? ORDER BY id ASC", MAPPER, sessionId);
    }

    public AiMessage findById(Long id) {
        return jdbcTemplate.queryForObject("SELECT * FROM ai_messages WHERE id = ?", MAPPER, id);
    }
}
