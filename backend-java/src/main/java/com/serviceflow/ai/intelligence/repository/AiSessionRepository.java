package com.serviceflow.ai.intelligence.repository;

import com.serviceflow.ai.intelligence.entity.AiSession;
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

/** AI 会话数据访问。 */
@Repository
public class AiSessionRepository {

    private static final String BASE_SELECT = """
            SELECT s.*, t.ticket_no, t.title AS ticket_title, u.name AS user_name,
                   (SELECT COUNT(*) FROM ai_messages m WHERE m.session_id = s.id) AS message_count
            FROM ai_sessions s
            JOIN tickets t ON t.id = s.ticket_id
            LEFT JOIN users u ON u.id = s.user_id
            """;

    private static final RowMapper<AiSession> MAPPER = (rs, rowNum) -> {
        AiSession s = new AiSession();
        s.setId(rs.getLong("id"));
        s.setTicketId(rs.getLong("ticket_id"));
        s.setUserId(rs.getLong("user_id"));
        s.setTitle(rs.getString("title"));
        s.setStatus(rs.getString("status"));
        s.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
        s.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        s.setTicketNo(rs.getString("ticket_no"));
        s.setTicketTitle(rs.getString("ticket_title"));
        s.setUserName(rs.getString("user_name"));
        s.setMessageCount(rs.getInt("message_count"));
        return s;
    };

    private final JdbcTemplate jdbcTemplate;

    public AiSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiSession insert(AiSession session) {
        String sql = """
                INSERT INTO ai_sessions (ticket_id, user_id, title, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, session.getTicketId());
            ps.setLong(2, session.getUserId());
            ps.setString(3, session.getTitle());
            ps.setString(4, session.getStatus());
            ps.setObject(5, now);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            session.setId(key.longValue());
        }
        return session;
    }

    public Optional<AiSession> findById(Long id) {
        List<AiSession> list = jdbcTemplate.query(BASE_SELECT + " WHERE s.id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<AiSession> findPage(Long userId, Long ticketId, String status, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(userId, ticketId, status, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(BASE_SELECT + where + " ORDER BY s.updated_at DESC, s.id DESC LIMIT ? OFFSET ?",
                MAPPER, args.toArray());
    }

    public long count(Long userId, Long ticketId, String status) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(userId, ticketId, status, args);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_sessions s " + where,
                Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String buildWhere(Long userId, Long ticketId, String status, List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (userId != null) {
            conditions.add("s.user_id = ?");
            args.add(userId);
        }
        if (ticketId != null) {
            conditions.add("s.ticket_id = ?");
            args.add(ticketId);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("s.status = ?");
            args.add(status);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    public void updateTitle(Long id, String title) {
        jdbcTemplate.update("UPDATE ai_sessions SET title = ?, updated_at = ? WHERE id = ?",
                title, LocalDateTime.now(), id);
    }

    public void updateStatus(Long id, String status) {
        jdbcTemplate.update("UPDATE ai_sessions SET status = ?, updated_at = ? WHERE id = ?",
                status, LocalDateTime.now(), id);
    }

    /** 只刷新更新时间，用于会话内新增消息后的排序。 */
    public void touch(Long id) {
        jdbcTemplate.update("UPDATE ai_sessions SET updated_at = ? WHERE id = ?",
                LocalDateTime.now(), id);
    }

    public int delete(Long id) {
        return jdbcTemplate.update("DELETE FROM ai_sessions WHERE id = ?", id);
    }
}
