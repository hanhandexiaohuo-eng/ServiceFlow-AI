package com.serviceflow.ai.knowledge.repository;

import com.serviceflow.ai.knowledge.entity.KbTicketLink;
import com.serviceflow.ai.knowledge.vo.KnowledgeLinkResponse;
import com.serviceflow.ai.knowledge.vo.LinkedTicketResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/** 知识与工单关联数据访问。 */
@Repository
public class KbTicketLinkRepository {

    private static final RowMapper<KnowledgeLinkResponse> BY_TICKET_MAPPER = (rs, rowNum) ->
            new KnowledgeLinkResponse(
                    rs.getLong("article_id"),
                    rs.getString("title"),
                    rs.getString("category"),
                    rs.getString("keywords"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getString("linked_by_name"),
                    rs.getString("note"));

    private static final RowMapper<LinkedTicketResponse> BY_ARTICLE_MAPPER = (rs, rowNum) ->
            new LinkedTicketResponse(
                    rs.getLong("ticket_id"),
                    rs.getString("ticket_no"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getString("linked_by_name"),
                    rs.getString("note"));

    private final JdbcTemplate jdbcTemplate;

    public KbTicketLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public KbTicketLink insert(KbTicketLink link) {
        String sql = "INSERT INTO kb_ticket_links (article_id, ticket_id, linked_by, note) VALUES (?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, link.getArticleId());
            ps.setLong(2, link.getTicketId());
            ps.setLong(3, link.getLinkedBy());
            ps.setString(4, link.getNote());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            link.setId(key.longValue());
        }
        return link;
    }

    public boolean exists(Long articleId, Long ticketId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_ticket_links WHERE article_id = ? AND ticket_id = ?",
                Long.class, articleId, ticketId);
        return count != null && count > 0;
    }

    public int delete(Long articleId, Long ticketId) {
        return jdbcTemplate.update(
                "DELETE FROM kb_ticket_links WHERE article_id = ? AND ticket_id = ?", articleId, ticketId);
    }

    public List<KnowledgeLinkResponse> findByTicketId(Long ticketId) {
        return jdbcTemplate.query("""
                        SELECT l.article_id, l.created_at, l.note, a.title, a.category, a.keywords,
                               a.status, u.name AS linked_by_name
                        FROM kb_ticket_links l
                        JOIN kb_articles a ON a.id = l.article_id
                        LEFT JOIN users u ON u.id = l.linked_by
                        WHERE l.ticket_id = ?
                        ORDER BY l.id DESC
                        """,
                BY_TICKET_MAPPER, ticketId);
    }

    public List<LinkedTicketResponse> findByArticleId(Long articleId) {
        return jdbcTemplate.query("""
                        SELECT l.ticket_id, l.created_at, l.note, t.ticket_no, t.title, t.status,
                               u.name AS linked_by_name
                        FROM kb_ticket_links l
                        JOIN tickets t ON t.id = l.ticket_id
                        LEFT JOIN users u ON u.id = l.linked_by
                        WHERE l.article_id = ?
                        ORDER BY l.id DESC
                        """,
                BY_ARTICLE_MAPPER, articleId);
    }
}
